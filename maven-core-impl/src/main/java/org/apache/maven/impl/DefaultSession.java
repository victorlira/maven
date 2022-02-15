package org.apache.maven.impl;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.maven.api.SessionData;
import org.apache.maven.api.services.LocalRepositoryManager;
import org.apache.maven.api.services.RepositoryFactory;
import org.apache.maven.api.services.Service;
import org.apache.maven.api.Session;
import org.apache.maven.api.Artifact;
import org.apache.maven.api.Metadata;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.Node;
import org.apache.maven.api.Project;
import org.apache.maven.api.LocalRepository;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.bridge.MavenRepositorySystem;
import org.apache.maven.project.MavenProject;
import org.apache.maven.api.services.ArtifactResolver;
import org.apache.maven.api.services.ArtifactDeployer;
import org.apache.maven.api.services.ArtifactFactory;
import org.apache.maven.api.services.ArtifactInstaller;
import org.apache.maven.api.services.DependencyCollector;
import org.apache.maven.api.services.DependencyFactory;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectDeployer;
import org.apache.maven.api.services.ProjectInstaller;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.spi.localrepo.LocalRepositoryManagerFactory;
import org.eclipse.aether.impl.LocalRepositoryProvider;

public class DefaultSession implements Session
{

    private final RepositorySystemSession session;
    private final RepositorySystem repositorySystem;
    private final LocalRepositoryManagerFactory localRepositoryManagerFactory;
    private final List<RemoteRepository> repositories;
    private final org.apache.maven.project.ProjectBuilder projectBuilder;
    private final MavenRepositorySystem mavenRepositorySystem;
    private LocalRepositoryProvider localRepositoryProvider;

    private final Map<org.eclipse.aether.graph.DependencyNode, Node> allNodes
            = Collections.synchronizedMap( new WeakHashMap<>() );
    private final Map<org.eclipse.aether.artifact.Artifact, Artifact> allArtifacts
            = Collections.synchronizedMap( new WeakHashMap<>() );
    private final Map<org.eclipse.aether.repository.RemoteRepository, RemoteRepository> allRepositories
            = Collections.synchronizedMap( new WeakHashMap<>() );
    private final Map<MavenProject, Project> allProjects
            = Collections.synchronizedMap( new WeakHashMap<>() );
    private final Map<org.eclipse.aether.graph.Dependency, Dependency> allDependencies
            = Collections.synchronizedMap( new WeakHashMap<>() );

    public DefaultSession( @Nonnull RepositorySystemSession session,
                           @Nonnull RepositorySystem repositorySystem,
                           @Nonnull LocalRepositoryManagerFactory localRepositoryManagerFactory,
                           @Nonnull List<RemoteRepository> repositories,
                           @Nonnull org.apache.maven.project.ProjectBuilder projectBuilder,
                           @Nonnull MavenRepositorySystem mavenRepositorySystem )
    {
        this.session = Objects.requireNonNull( session );
        this.repositorySystem = Objects.requireNonNull( repositorySystem );
        this.localRepositoryManagerFactory = Objects.requireNonNull( localRepositoryManagerFactory );
        this.repositories = Objects.requireNonNull( repositories );
        this.projectBuilder = projectBuilder;
        this.mavenRepositorySystem = mavenRepositorySystem;
    }

    @Nonnull
    @Override
    public LocalRepository getLocalRepository()
    {
        return new DefaultLocalRepository( session.getLocalRepository() );
    }

    @Nonnull
    @Override
    public List<RemoteRepository> getRemoteRepositories()
    {
        return Collections.unmodifiableList( repositories );
    }

    @Nonnull
    @Override
    public Settings getSettings()
    {
        return null;
    }

    @Nonnull
    @Override
    public Properties getUserProperties()
    {
        return null;
    }

    @Nonnull
    @Override
    public Properties getSystemProperties()
    {
        return null;
    }

    @Nonnull
    @Override
    public SessionData getData()
    {
        org.eclipse.aether.SessionData data = session.getData();
        return new SessionData()
        {
            @Override
            public void set( @Nonnull Object key, @Nullable Object value )
            {
                data.set( key, value );
            }
            @Override
            public boolean set( @Nonnull Object key, @Nullable Object oldValue, @Nullable Object newValue )
            {
                return data.set( key, oldValue, newValue );
            }
            @Nullable
            @Override
            public Object get( @Nonnull Object key )
            {
                return data.get( key );
            }
            @Nullable
            @Override
            public Object computeIfAbsent( @Nonnull Object key, @Nonnull Supplier<Object> supplier )
            {
                return data.computeIfAbsent( key, supplier );
            }
        };
    }

    @Nonnull
    @Override
    public Session withLocalRepository( @Nonnull LocalRepository localRepository )
    {
        Objects.requireNonNull( localRepository, "localRepository" );
        if ( session.getLocalRepository() != null
                && Objects.equals( session.getLocalRepository().getBasedir().toPath(),
                             localRepository.getPath() ) )
        {
            return this;
        }
        try
        {
            org.eclipse.aether.repository.LocalRepository repository = toRepository( localRepository );
            org.eclipse.aether.repository.LocalRepositoryManager localRepositoryManager
                    = localRepositoryManagerFactory.newInstance( session, repository );

            RepositorySystemSession newSession = new DefaultRepositorySystemSession( session )
                    .setLocalRepositoryManager( localRepositoryManager );

            return new DefaultSession( newSession, repositorySystem, localRepositoryManagerFactory,
                                       repositories, projectBuilder, mavenRepositorySystem );
        }
        catch ( NoLocalRepositoryManagerException e )
        {
            throw new IllegalArgumentException( "Unsupported repository layout", e );
        }

    }

    @Nonnull
    @Override
    public Session withRemoteRepositories( @Nonnull List<RemoteRepository> repositories )
    {
        return new DefaultSession( session, repositorySystem, localRepositoryManagerFactory,
                                   repositories, projectBuilder, mavenRepositorySystem );
    }

    @Nonnull
    @Override
    @SuppressWarnings( "unchecked" )
    public <T extends Service> T getService( Class<T> clazz ) throws NoSuchElementException
    {
        if ( clazz == ArtifactFactory.class )
        {
            return (T) new DefaultArtifactFactory();
        }
        else if ( clazz == ArtifactResolver.class )
        {
            return (T) new DefaultArtifactResolver( repositorySystem );
        }
        else if ( clazz == ArtifactDeployer.class )
        {
            return (T) new DefaultArtifactDeployer( repositorySystem );
        }
        else if ( clazz == ArtifactInstaller.class )
        {
            return (T) new DefaultArtifactInstaller( repositorySystem );
        }
        else if ( clazz == DependencyFactory.class )
        {
            return (T) new DefaultDependencyFactory();
        }
        else if ( clazz == DependencyCollector.class )
        {
            return (T) new DefaultDependencyCollector( repositorySystem );
        }
        else if ( clazz == DependencyResolver.class )
        {
            return (T) new DefaultDependencyResolver( repositorySystem );
        }
        else if ( clazz == ProjectBuilder.class )
        {
            return (T) new DefaultProjectBuilder( projectBuilder );
        }
        else if ( clazz == ProjectDeployer.class )
        {
            return (T) new DefaultProjectDeployer( repositorySystem );
        }
        else if ( clazz == ProjectInstaller.class )
        {
            return (T) new DefaultProjectInstaller( repositorySystem );
        }
        else if ( clazz == LocalRepositoryManager.class )
        {
            return (T) new DefaultLocalRepositoryManager();
        }
        else if ( clazz == RepositoryFactory.class )
        {
            return (T) new DefaultRepositoryFactory();
        }
        throw new NoSuchElementException( clazz.getName() );
    }

    public RepositorySystemSession getSession()
    {
        return session;
    }

    public LocalRepositoryProvider getLocalRepositoryProvider()
    {
        return localRepositoryProvider;
    }

    public RemoteRepository getRemoteRepository( org.eclipse.aether.repository.RemoteRepository repository )
    {
        return allRepositories.computeIfAbsent( repository, DefaultRemoteRepository::new );
    }

    public Node getNode( org.eclipse.aether.graph.DependencyNode node )
    {
        return allNodes.computeIfAbsent( node, n -> new DefaultNode( this, n ) );
    }

    public Artifact getArtifact( org.eclipse.aether.artifact.Artifact artifact )
    {
        return allArtifacts.computeIfAbsent( artifact, a -> new DefaultArtifact( this, a ) );
    }

    public Dependency getDependency( org.eclipse.aether.graph.Dependency dependency )
    {
        return allDependencies.computeIfAbsent( dependency, d -> new DefaultDependency( this, d ) );
    }

    public Project getProject( MavenProject project )
    {
        return allProjects.computeIfAbsent( project, p -> new DefaultProject( this, p ) );
    }

    public List<org.eclipse.aether.repository.RemoteRepository> toRepositories( List<RemoteRepository> repositories )
    {
        return repositories == null ? null : repositories.stream()
                .map( this::toRepository )
                .collect( Collectors.toList() );
    }

    public org.eclipse.aether.repository.RemoteRepository toRepository( RemoteRepository repository )
    {
        if ( repository instanceof DefaultRemoteRepository )
        {
            return ( (DefaultRemoteRepository) repository ).getRepository();
        }
        else
        {
            // TODO
            throw new UnsupportedOperationException( "Not implemented yet" );
        }
    }

    public org.eclipse.aether.repository.LocalRepository toRepository( LocalRepository repository )
    {
        if ( repository instanceof DefaultLocalRepository )
        {
            return ( ( DefaultLocalRepository ) repository ).getRepository();
        }
        else
        {
            // TODO
            throw new UnsupportedOperationException( "Not implemented yet" );
        }
    }

    public List<ArtifactRepository> toArtifactRepositories( List<RemoteRepository> repositories )
    {
        return repositories == null ? null : repositories.stream()
                .map( this::toArtifactRepository )
                .collect( Collectors.toList() );
    }

    private ArtifactRepository toArtifactRepository( RemoteRepository repository )
    {
        if ( repository instanceof DefaultRemoteRepository )
        {
            org.eclipse.aether.repository.RemoteRepository rr
                    = ( (DefaultRemoteRepository) repository ).getRepository();

            try
            {
                return mavenRepositorySystem.createRepository(
                        rr.getUrl(),
                        rr.getId(),
                        rr.getPolicy( false ).isEnabled(),
                        rr.getPolicy( false ).getUpdatePolicy(),
                        rr.getPolicy( true ).isEnabled(),
                        rr.getPolicy( true ).getUpdatePolicy(),
                        rr.getPolicy( false ).getChecksumPolicy()

                );
            }
            catch ( Exception e )
            {
                throw new RuntimeException( "Unable to create repository", e );
            }
        }
        else
        {
            // TODO
            throw new UnsupportedOperationException( "Not yet implemented" );
        }
    }

    public List<org.eclipse.aether.graph.Dependency> toDependencies( Collection<Dependency> dependencies )
    {
        return dependencies == null ? null : dependencies.stream()
                .map( this::toDependency )
                .collect( Collectors.toList() );
    }

    public org.eclipse.aether.graph.Dependency toDependency( Dependency dependency )
    {
        if ( dependency instanceof DefaultDependency )
        {
            return ( ( DefaultDependency ) dependency ).getDependency();
        }
        else
        {
            String typeId = dependency.getType();
            org.eclipse.aether.artifact.ArtifactType type = typeId != null
                    ? session.getArtifactTypeRegistry().get( typeId ) : null;
            String extension = type != null ? type.getExtension() : null;
            return new org.eclipse.aether.graph.Dependency(
                    new org.eclipse.aether.artifact.DefaultArtifact(
                            dependency.getGroupId(),
                            dependency.getArtifactId(),
                            dependency.getClassifier(),
                            extension,
                            dependency.getVersion(),
                            type
                    ), null );
        }
    }

    public List<org.eclipse.aether.artifact.Artifact> toArtifacts( Collection<Artifact> artifacts )
    {
        return artifacts == null ? null : artifacts.stream()
                .map( this::toArtifact )
                .collect( Collectors.toList() );
    }

    public org.eclipse.aether.artifact.Artifact toArtifact( Artifact artifact )
    {
        if ( artifact instanceof DefaultArtifact )
        {
            return ( ( DefaultArtifact ) artifact ).getArtifact();
        }
        else
        {
            return new org.eclipse.aether.artifact.DefaultArtifact(
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getClassifier(),
                    artifact.getExtension(),
                    artifact.getVersion(),
                    null,
                    ( (Artifact) artifact ).getPath().map( Path::toFile ).orElse( null )
            );
        }
    }

    public org.eclipse.aether.metadata.Metadata toMetadata( Metadata metadata )
    {
        /*
        if ( metadata instanceof ProjectArtifactMetadata )
        {
            Artifact pomArtifact = new SubArtifact( mainArtifact, "", "pom" );
            pomArtifact = pomArtifact.setFile( ( (ProjectArtifactMetadata) metadata ).getFile() );
            request.addArtifact( pomArtifact );
        }
        else if ( // metadata instanceof SnapshotArtifactRepositoryMetadata ||
                metadata instanceof ArtifactRepositoryMetadata )
        {
            // eaten, handled by repo system
        }
        else if ( metadata instanceof org.apache.maven.shared.transfer.metadata.ArtifactMetadata )
        {
            org.apache.maven.shared.transfer.metadata.ArtifactMetadata transferMetadata =
                    (org.apache.maven.shared.transfer.metadata.ArtifactMetadata) metadata;

            request.addMetadata( new Maven31MetadataBridge( metadata ).setFile( transferMetadata.getFile() ) );
        }

         */
        // TODO
        throw new UnsupportedOperationException( "Not implemented yet" );
    }

}