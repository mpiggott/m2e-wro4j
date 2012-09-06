/*******************************************************************************
 * Copyright (c) 2012 Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.jboss.tools.m2e.wro4j.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Set;

import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.Scanner;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.embedder.IMaven;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.MavenProjectUtils;
import org.eclipse.m2e.core.project.configurator.MojoExecutionBuildParticipant;
import org.eclipse.osgi.util.NLS;
import org.sonatype.plexus.build.incremental.BuildContext;

public class Wro4jBuildParticipant
    extends MojoExecutionBuildParticipant
{

    private static final String DESTINATION_FOLDER = "destinationFolder";

    private static final String CSS_DESTINATION_FOLDER = "cssDestinationFolder";

    private static final String JS_DESTINATION_FOLDER = "jsDestinationFolder";

    private WroModel model;

    private Boolean pomModified;

    private String[] changedFiles;

    public Wro4jBuildParticipant( MojoExecution execution )
    {
        super( execution, true );
    }

    @Override
    public Set<IProject> build( int kind, IProgressMonitor monitor )
        throws Exception
    {
        if ( monitor == null )
        {
            monitor = new NullProgressMonitor();
        }
        monitor =
            SubMonitor.convert( monitor, NLS.bind( "Invoking {0} on {1}",
                                                   getMojoExecution().getMojoDescriptor().getFullGoalName(),
                                                   getMavenProjectFacade().getProject().getName() ), 10 );

        MojoExecution mojoExecution = getMojoExecution();
        if ( mojoExecution == null )
        {
            return null;
        }

        BuildContext buildContext = getBuildContext();
        if ( !wroResourceChangeDetected( mojoExecution, buildContext ) )
        {
            return null;
        }

        if ( monitor.isCanceled() )
        {
            throw new CoreException( Status.CANCEL_STATUS );
        }
        monitor.worked( 1 );

        Xpp3Dom originalConfiguration = mojoExecution.getConfiguration();

        Set<IProject> result = null;
        try
        {
            File destinationFolder = getFolder( mojoExecution, DESTINATION_FOLDER );
            File jsDestinationFolder = getFolder( mojoExecution, JS_DESTINATION_FOLDER );
            File cssDestinationFolder = getFolder( mojoExecution, CSS_DESTINATION_FOLDER );
            File source = getFolder( mojoExecution, "contextFolder" );

            Xpp3Dom customConfiguration =
                customize( originalConfiguration, destinationFolder, jsDestinationFolder, cssDestinationFolder );
            // Add custom configuration
            mojoExecution.setConfiguration( customConfiguration );
            if ( monitor.isCanceled() )
            {
                throw new CoreException( Status.CANCEL_STATUS );
            }
            monitor.worked( 1 );

            final String[] targets =
                MavenPlugin.getMaven().getMojoParameterValue( getSession(), mojoExecution, "targetGroups", String.class ).split( "," );

            monitor = SubMonitor.convert( monitor, targets.length );

            for ( String target : targets )
            {
                if ( monitor.isCanceled() )
                {
                    throw new CoreException( Status.CANCEL_STATUS );
                }
                target = target.trim();
                if ( isPomModified() || wroTargetChangeDetected( target, mojoExecution, buildContext ) )
                {
                    createFile( new File( cssDestinationFolder, target + ".css" ), source,
                                getCss( mojoExecution, target ), "\n" );
                    createFile( new File( jsDestinationFolder, target + ".js" ), source,
                                getJs( mojoExecution, target ), ";\n" );
                }
                monitor.worked( 1 );
            }
        }
        finally
        {
            // restore original configuration
            mojoExecution.setConfiguration( originalConfiguration );
        }

        return result;
    }

    private File getFolder( MojoExecution mojoExecution, String folderName )
        throws CoreException
    {
        IMaven maven = MavenPlugin.getMaven();
        File folder = maven.getMojoParameterValue( getSession(), mojoExecution, folderName, File.class );
        return folder;
    }

    private boolean wroResourceChangeDetected( MojoExecution mojoExecution, BuildContext buildContext )
        throws CoreException
    {

        // If the pom file changed, we force wro4j's invocation
        if ( isPomModified() )
        {
            return true;
        }

        return getChangedFiles( mojoExecution, buildContext ).length > 0;

    }

    private boolean wroTargetChangeDetected( String target, MojoExecution mojoExecution, BuildContext buildContext )
        throws CoreException
    {
        Collection<String> js = getJs( mojoExecution, target );
        Collection<String> css = getCss( mojoExecution, target );
        for ( String file : getChangedFiles( mojoExecution, buildContext ) )
        {
            file = '/' + file.replace( '\\', '/' );
            if ( js.contains( file ) || css.contains( file ) )
            {
                return true;
            }
        }
        return false;

    }

    private boolean isPomModified()
    {
        if ( pomModified == null )
        {
            IMavenProjectFacade facade = getMavenProjectFacade();
            IResourceDelta delta = getDelta( facade.getProject() );
            if ( delta == null )
            {
                pomModified = Boolean.FALSE;
            }
            else if ( delta.findMember( facade.getPom().getProjectRelativePath() ) != null )
            {
                pomModified = Boolean.TRUE;
            }
            else
            {
                pomModified = Boolean.FALSE;
            }
        }
        return pomModified;
    }

    private String[] getChangedFiles( MojoExecution mojoExecution, BuildContext buildContext )
        throws CoreException
    {
        if ( changedFiles == null )
        {
            // check if any of the web resource files changed
            File source = getFolder( mojoExecution, "contextFolder" );
            // TODO also analyze output classes folders as wro4j can use classpath files
            Scanner ds = buildContext.newScanner( source ); // delta or full scanner
            ds.scan();
            changedFiles = ds.getIncludedFiles();
            if ( changedFiles == null )
            {
                changedFiles = new String[0];
            }
        }
        return changedFiles;
    }

    private Xpp3Dom customize( Xpp3Dom originalConfiguration, File originalDestinationFolder,
                               File originalJsDestinationFolder, File originalCssDestinationFolder )
        throws IOException
    {
        IMavenProjectFacade facade = getMavenProjectFacade();
        if ( !"war".equals( facade.getPackaging() ) )
        {
            // Not a war project, we don't know how to customize that
            return originalConfiguration;
        }

        IProject project = facade.getProject();
        String target = facade.getMavenProject().getBuild().getDirectory();
        IPath relativeTargetPath = MavenProjectUtils.getProjectRelativePath( project, target );
        if ( relativeTargetPath == null )
        {
            // target folder not under the project directory, we bail
            return originalConfiguration;
        }

        IFolder webResourcesFolder =
            project.getFolder( relativeTargetPath.append( "m2e-wtp" ).append( "web-resources" ) );
        if ( !webResourcesFolder.exists() )
        {
            // Not a m2e-wtp project, we don't know how to customize either
            // TODO Try to support Sonatype's webby instead?
            return originalConfiguration;
        }

        IPath fullTargetPath = new Path( target );
        IPath defaultOutputPathPrefix = fullTargetPath.append( facade.getMavenProject().getBuild().getFinalName() );

        Xpp3Dom customConfiguration = new Xpp3Dom( "configuration" );
        Xpp3DomUtils.mergeXpp3Dom( customConfiguration, originalConfiguration );

        customizeFolder( originalDestinationFolder, webResourcesFolder, defaultOutputPathPrefix, customConfiguration, DESTINATION_FOLDER );

        customizeFolder( originalJsDestinationFolder, webResourcesFolder, defaultOutputPathPrefix, customConfiguration, JS_DESTINATION_FOLDER );

        customizeFolder( originalCssDestinationFolder, webResourcesFolder, defaultOutputPathPrefix, customConfiguration, CSS_DESTINATION_FOLDER );

        return customConfiguration;
    }

    private void customizeFolder( File originalDestinationFolder, IFolder webResourcesFolder,
                                  IPath defaultOutputPathPrefix, Xpp3Dom configuration, String folderParameterName )
        throws IOException
    {

        if ( originalDestinationFolder != null )
        {
            IPath customPath =
                getReplacementPath( originalDestinationFolder, webResourcesFolder, defaultOutputPathPrefix );
            if ( customPath != null )
            {
                Xpp3Dom dom = configuration.getChild( folderParameterName );
                if ( dom == null )
                {
                    dom = new Xpp3Dom( folderParameterName );
                    configuration.addChild( dom );
                }
                dom.setValue( customPath.toOSString() );
            }
        }
    }

    private IPath getReplacementPath( File originalFolder, IFolder webResourcesFolder, IPath defaultOutputPathPrefix )
        throws IOException
    {
        IPath originalDestinationFolderPath = Path.fromOSString( originalFolder.getCanonicalPath() );

        if ( !defaultOutputPathPrefix.isPrefixOf( originalDestinationFolderPath ) )
        {
            return null;
        }

        IPath relativePath = originalDestinationFolderPath.makeRelativeTo( defaultOutputPathPrefix );
        IPath customPath = webResourcesFolder.getLocation().append( relativePath );
        return customPath;
    }

    private Collection<String> getJs( MojoExecution mojoExecution, String groupId )
        throws CoreException
    {
        return getGroups( mojoExecution ).getGroup( groupId ).getJs();
    }

    private Collection<String> getCss( MojoExecution mojoExecution, String groupId )
        throws CoreException
    {
        return getGroups( mojoExecution ).getGroup( groupId ).getCss();
    }

    private WroModel getGroups( MojoExecution mojoExecution )
        throws CoreException
    {
        if ( model == null )
        {
            WroModel model = new WroModel();
            InputStream in = null;
            try
            {
                in =
                    new FileInputStream( MavenPlugin.getMaven().getMojoParameterValue( getSession(), mojoExecution,
                                                                                       "wroFile", File.class ) );
                model.read( in );
            }
            catch ( Exception e )
            {
                IOUtil.close( in );
                throw new CoreException( new Status( IStatus.ERROR, "org.jboss.tools.m2e.wro4j.core",
                                                     "Failed to load WRO4J model", e ) );
            }
            this.model = model;
        }
        return model;
    }

    private void createFile( File destination, File contextFolder, Collection<String> files, String separator )
        throws CoreException
    {
        File parent = destination.getParentFile();
        if ( !parent.exists() && !destination.getParentFile().mkdirs() )
        {
            throw new CoreException( new Status( IStatus.ERROR, "org.jboss.tools.m2e.wro4j.core",
                                                 "Failed to parents directories: " + destination.getAbsolutePath() ) );
        }
        OutputStream out = null;
        InputStream in = null;
        try
        {
            out = new FileOutputStream( destination );
            for ( String file : files )
            {
                in = new FileInputStream( new File( contextFolder, file ) );
                IOUtil.copy( in, out );
                in.close();
                if ( separator != null )
                {
                    out.write( separator.getBytes() );
                }
            }
        }
        catch ( Exception e )
        {
            throw new CoreException( new Status( IStatus.ERROR, "org.jboss.tools.m2e.wro4j.core",
                                                 "Error creating file: " + destination.getAbsolutePath(), e ) );
        }
        finally
        {
            IOUtil.close( in );
            IOUtil.close( out );
        }
    }
}
