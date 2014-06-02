/*******************************************************************************
 * Copyright (c) 2014 Red Hat, Inc..
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.commonjava.maven.ext.manip.resolver;

import java.io.File;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.repository.MirrorSelector;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.RepositoryPolicy;
import org.apache.maven.settings.Settings;
import org.commonjava.maven.atlas.ident.util.JoinString;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Resource;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.commonjava.maven.galley.model.VirtualResource;
import org.commonjava.maven.galley.spi.transport.LocationExpander;
import org.commonjava.maven.galley.transport.htcli.model.SimpleHttpLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Galley {@link LocationExpander} implementation that expands a shorthand URI
 * given in the betterdep goals into the actual list of locations to check for
 * artifacts.
 * 
 * @author jdcasey
 */
public class MavenLocationExpander
    implements LocationExpander
{

    public static final Location EXPANSION_TARGET = new SimpleLocation( "maven:repositories" );

    private final List<Location> locations;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public MavenLocationExpander( final List<Location> customLocations,
                                  final List<ArtifactRepository> artifactRepositories,
                                  final ArtifactRepository localRepository, final MirrorSelector mirrorSelector,
                                  final Settings settings, final List<String> activeProfiles )
        throws MalformedURLException
    {
        final Set<Location> locs = new LinkedHashSet<Location>();

        if ( localRepository != null )
        {
            locs.add( new SimpleLocation( localRepository.getId(), new File( localRepository.getBasedir() ).toURI()
                                                                                                           .toString() ) );
        }

        if ( customLocations != null )
        {
            locs.addAll( customLocations );
        }

        addSettingsProfileRepositoriesTo( locs, settings, activeProfiles, mirrorSelector );
        addRequestRepositoriesTo( locs, artifactRepositories, settings, mirrorSelector );

        logger.debug( "Configured to use Maven locations:\n  {}", new JoinString( "\n  ", locs ) );
        this.locations = new ArrayList<Location>( locs );
    }

    private void addRequestRepositoriesTo( final Set<Location> locs,
                                           final List<ArtifactRepository> artifactRepositories,
                                           final Settings settings, final MirrorSelector mirrorSelector )
        throws MalformedURLException
    {
        if ( artifactRepositories != null )
        {
            for ( final ArtifactRepository repo : artifactRepositories )
            {
                // TODO: Authentication via memory password manager.
                String id = repo.getId();
                String url = repo.getUrl();

                if ( url.startsWith( "file:" ) )
                {
                    locs.add( new SimpleLocation( id, url ) );
                }
                else
                {
                    final List<Mirror> mirrors = settings.getMirrors();
                    if ( mirrors != null )
                    {
                        final Mirror mirror = mirrorSelector == null ? null : mirrorSelector.getMirror( repo, mirrors );
                        if ( mirror != null )
                        {
                            id = mirror.getId();
                            url = mirror.getUrl();
                        }
                    }

                    final ArtifactRepositoryPolicy releases = repo.getReleases();
                    final ArtifactRepositoryPolicy snapshots = repo.getSnapshots();

                    locs.add( new SimpleHttpLocation( id, url, snapshots == null ? false : snapshots.isEnabled(),
                                                      releases == null ? true : releases.isEnabled(), true, false, -1,
                                                      null ) );
                }
            }
        }
    }

    private void addSettingsProfileRepositoriesTo( final Set<Location> locs, final Settings settings,
                                                   final List<String> activeProfiles,
                                                   final MirrorSelector mirrorSelector )
        throws MalformedURLException
    {
        if ( settings != null )
        {
            final Map<String, Profile> profiles = settings.getProfilesAsMap();
            if ( profiles != null && activeProfiles != null && !activeProfiles.isEmpty() )
            {
                final LinkedHashSet<String> active = new LinkedHashSet<String>( activeProfiles );

                final List<String> settingsActiveProfiles = settings.getActiveProfiles();
                if ( settingsActiveProfiles != null && !settingsActiveProfiles.isEmpty() )
                {
                    active.addAll( settingsActiveProfiles );
                }

                for ( final String profileId : active )
                {
                    final Profile profile = profiles.get( profileId );
                    if ( profile != null )
                    {
                        final List<Repository> repositories = profile.getRepositories();
                        if ( repositories != null )
                        {
                            final List<Mirror> mirrors = settings.getMirrors();
                            final ArtifactRepositoryLayout layout = new DefaultRepositoryLayout();
                            for ( final Repository repo : repositories )
                            {
                                String id = repo.getId();
                                String url = repo.getUrl();

                                if ( mirrors != null )
                                {
                                    final ArtifactRepositoryPolicy snapshots = convertPolicy( repo.getSnapshots() );
                                    final ArtifactRepositoryPolicy releases = convertPolicy( repo.getReleases() );

                                    final MavenArtifactRepository arepo =
                                        new MavenArtifactRepository( id, url, layout, snapshots, releases );

                                    final Mirror mirror =
                                        mirrorSelector == null ? null : mirrorSelector.getMirror( arepo, mirrors );

                                    if ( mirror != null )
                                    {
                                        id = mirror.getId();
                                        url = mirror.getUrl();
                                    }

                                    locs.add( new SimpleHttpLocation( id, url, snapshots == null ? false
                                                    : snapshots.isEnabled(), releases == null ? true
                                                    : releases.isEnabled(), true, false, -1, null ) );
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    private ArtifactRepositoryPolicy convertPolicy( final RepositoryPolicy policy )
    {
        if ( policy == null )
        {
            return new ArtifactRepositoryPolicy();
        }

        return new ArtifactRepositoryPolicy( policy.isEnabled(), policy.getUpdatePolicy(), policy.getChecksumPolicy() );
    }

    @Override
    public List<Location> expand( final Location... locations )
        throws TransferException
    {
        final List<Location> result = new ArrayList<Location>();
        for ( final Location loc : locations )
        {
            expandSingle( loc, result );
        }

        logger.debug( "Expanded to:\n {}", new JoinString( "\n  ", result ) );
        return result;
    }

    @Override
    public <T extends Location> List<Location> expand( final Collection<T> locations )
        throws TransferException
    {
        final List<Location> result = new ArrayList<Location>();
        for ( final Location loc : locations )
        {
            expandSingle( loc, result );
        }

        logger.debug( "Expanded to:\n {}", new JoinString( "\n  ", result ) );
        return result;
    }

    @Override
    public VirtualResource expand( final Resource resource )
        throws TransferException
    {
        final List<Location> result = new ArrayList<Location>();
        if ( resource instanceof ConcreteResource )
        {
            final Location loc = ( (ConcreteResource) resource ).getLocation();
            expandSingle( loc, result );
        }
        else
        {
            for ( final Location loc : ( (VirtualResource) resource ).getLocations() )
            {
                expandSingle( loc, result );
            }
        }

        logger.debug( "Expanded to:\n {}", new JoinString( "\n  ", result ) );
        return new VirtualResource( result, resource.getPath() );
    }

    private void expandSingle( final Location loc, final List<Location> result )
    {
        logger.debug( "Expanding: {}", loc );
        if ( EXPANSION_TARGET.equals( loc ) )
        {
            logger.debug( "Expanding..." );
            result.addAll( this.locations );
        }
        else
        {
            logger.debug( "No expansion available." );
            result.add( loc );
        }
    }

}