package org.apache.maven.plugin.cxx;

/*
 * Copyright (C) 2011-2016, Neticoa SAS France - Tous droits réservés.
 * Author(s) : Franck Bonin, Neticoa SAS France
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

import org.apache.commons.io.IOUtils;
import org.codehaus.plexus.util.StringUtils;
import org.apache.commons.lang.text.StrSubstitutor;
import org.apache.maven.plugin.ContextEnabled;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.URL;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Collection;
import java.util.HashMap;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.Enumeration;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import org.apache.maven.plugin.cxx.utils.svn.SvnService;
import org.apache.maven.plugin.cxx.utils.svn.SvnInfo;

/**
 * Generates a new project from an archetype, or updates the actual project if using a partial archetype.
 * If the project is generated or updated in the current directory.
 * mvn cxx:generate -DartifactName="an-id" -DartifactId="AnId"
 * 
 * @author Franck Bonin 
 * @since 0.0.6
 */
@Mojo( name = "generate", requiresProject = false )
@Execute( phase = LifecyclePhase.GENERATE_SOURCES )
public class GenerateMojo
    extends AbstractCxxMojo
    implements ContextEnabled
{
    /**
     * The archetype's artifactId 
     * {cmake-cpp-project | source-project | aggregator-pom | project-parent-pom | cpp-super-pom }
     * 
     * @since 0.0.6
     */
    @Parameter( property = "archetypeArtifactId", defaultValue = "cmake-cpp-project" )
    private String archetypeArtifactId;

    /**
     * The archetype's groupId (not used, reserved for futur usage).
     * 
     * @since 0.0.6
     */
    @Parameter( property = "archetypeGroupId", defaultValue = "org.apache.maven.plugin.cxx" )
    private String archetypeGroupId;

    /**
     * The archetype's version (not used, reserved for futur usage).
     * @since 0.0.6
     */
    @Parameter( property = "archetypeVersion", defaultValue = "0.1" )
    private String archetypeVersion;
    
    /**
     * The generated pom parent groupId.
     * 
     * @since 0.0.6
     */
    @Parameter( property = "parentGroupId", defaultValue = "fr.neticoa" )
    private String parentGroupId;
    
    /**
     * The generated pom parent artifactId.
     * 
     * @since 0.0.6
     */
    @Parameter( property = "parentArtifactId", defaultValue = "cpp-super-pom" ) 
    private String parentArtifactId;
    
    /**
     * The generated pom parent version.
     * 
     * @since 0.0.6
     */
    @Parameter( property = "parentVersion", defaultValue = "1.1.0.0" )
    private String parentVersion;
    
    /**
     * The generated pom groupId.
     * 
     * @since 0.0.6
     */
    @Parameter( property = "groupId", defaultValue = "fr.neticoa" )
    private String groupId;
    
    /**
     * The generated pom artifact Name.
     * 
     * @since 0.0.6
     */
    @Parameter( property = "artifactName", required = true )
    private String artifactName;
     
    /**
     * The generated pom artifactId
     * 
     * @since 0.0.6
     */
    @Parameter( property = "artifactId", required = true )
    private String artifactId;
    
    /**
     * The generated pom version.
     * 
     * @since 0.0.6
     */
    @Parameter( property = "version", defaultValue = "0.0.0.1" )
    private String version;
    
    /**
     * The generated CMakeLists file CMake min version.
     * 
     * @since 0.0.6
     */
    @Parameter( property = "cmakeMinVersion", defaultValue = "3.0.0" )
    private String cmakeMinVersion;
    
    protected Map<String, String> listResourceFolderContent( String path, Map valuesMap )
    {
        String location = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
        final File jarFile = new File( location );
        
        HashMap<String, String> resources = new HashMap<String, String>();
        StrSubstitutor substitutor = new StrSubstitutor( valuesMap );
        
        path = ( StringUtils.isEmpty( path ) ) ? "" : path + "/";
        getLog().debug( "listResourceFolderContent : " + location + ", sublocation : " + path );
        if ( jarFile.isFile() )
        {
            getLog().debug( "listResourceFolderContent : jar case" );
            try
            {
                final JarFile jar = new JarFile( jarFile );
                final Enumeration<JarEntry> entries = jar.entries();
                while ( entries.hasMoreElements() )
                {
                    final String name = entries.nextElement().getName();
                    if ( name.startsWith( path ) )
                    { 
                        String resourceFile = File.separator + name;
                        if ( !( resourceFile.endsWith( "/" ) || resourceFile.endsWith( "\\" ) ) )
                        {
                            getLog().debug( "resource entry = " + resourceFile );
                            String destFile = substitutor.replace( resourceFile );
                            getLog().debug( "become entry = " + destFile );
                            resources.put( resourceFile, destFile );
                        }
                    }
                }
                jar.close();
            }
            catch ( IOException ex )
            {
                getLog().error( "unable to list jar content : " + ex );
            }
        }
        else
        {
            getLog().debug( "listResourceFolderContent : file case" );
            //final URL url = Launcher.class.getResource("/" + path);
            final URL url = getClass().getResource( "/" + path );
            if ( url != null )
            {
                try
                {
                    final File names = new File( url.toURI() );
                    Collection<File> entries = 
                        FileUtils.listFiles( names, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE );
                    for ( File name : entries )
                    {
                        String resourceFile = name.getPath();
                        if ( !( resourceFile.endsWith( "/" ) || resourceFile.endsWith( "\\" ) ) )
                        {
                            getLog().debug( "resource entry = " + resourceFile );
                            String destFile = substitutor.replace( resourceFile );
                            destFile = destFile.replaceFirst( Pattern.quote( location ), "/" );
                            getLog().debug( "become entry = " + destFile );
                            resources.put( resourceFile, destFile );
                        }
                    }
                }
                catch ( URISyntaxException ex )
                {
                    // never happens
                }
            }
        }
        return resources;
    }

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        //Properties systemProperties = session.getSystemProperties();
        //Properties userProperties = session.getUserProperties();
        //Properties properties = session.getExecutionProperties();
        
        org.apache.maven.artifact.versioning.DefaultArtifactVersion defautCMakeVersion =
            new org.apache.maven.artifact.versioning.DefaultArtifactVersion( "3.0.0" );
        org.apache.maven.artifact.versioning.DefaultArtifactVersion askedCMakeVersion =
            new org.apache.maven.artifact.versioning.DefaultArtifactVersion( cmakeMinVersion );
        boolean bCMake3OrAbove = ( askedCMakeVersion.compareTo( defautCMakeVersion ) >= 0 );
        
        getLog().debug( "CMake 3 or above asked (" + cmakeMinVersion + ") ? " + ( bCMake3OrAbove ? "yes" : "no" ) );
        
        HashMap<String, String> valuesMap = new HashMap<String, String>();
        valuesMap.put( "parentGroupId", parentGroupId );
        valuesMap.put( "parentArtifactId", parentArtifactId );
        valuesMap.put( "parentVersion", parentVersion );
        valuesMap.put( "groupId", groupId );
        valuesMap.put( "artifactId", artifactId );
        valuesMap.put( "artifactName", artifactName );
        valuesMap.put( "version", version );
        valuesMap.put( "cmakeMinVersion", cmakeMinVersion );
        valuesMap.put( "parentScope", bCMake3OrAbove ? "PARENT_SCOPE" : "" );
        valuesMap.put( "projectVersion", bCMake3OrAbove ? "VERSION ${TARGET_VERSION}" : "" );
        valuesMap.put( "scmConnection", "" );

//1/ search for properties
// -DgroupId=fr.neticoa -DartifactName=QtUtils -DartifactId=qtutils -Dversion=1.0-SNAPSHOT

        if ( StringUtils.isEmpty( archetypeArtifactId ) )
        {
            throw new MojoExecutionException( "archetypeArtifactId is empty " );
        }

        Map<String, String> resources = listResourceFolderContent( archetypeArtifactId, valuesMap );
        
        if ( null == resources || resources.size() == 0 )
        {
            throw new MojoExecutionException( "Unable to find archetype : " + archetypeArtifactId );
        }
//1.1/ search potential scm location of current dir
        // svn case
        SvnInfo basedirSvnInfo = SvnService.getSvnInfo( basedir, null, basedir.getAbsolutePath(), getLog(), true );
        if ( basedirSvnInfo.isValide() )
        {
            valuesMap.put( "scmConnection", "scm:svn:" + basedirSvnInfo.getSvnUrl() );
        }
        // todo : handle other scm : git (git remote -v; git log --max-count=1), etc.
        
//2/ unpack resource to destdir 
        getLog().info( "archetype " + archetypeArtifactId + " has " + resources.entrySet().size() + " item(s)" );
        getLog().info( "basedir = " + basedir );
                
        StrSubstitutor substitutor = new StrSubstitutor( valuesMap, "$(", ")" );
        String sExecutionDate = new SimpleDateFormat( "yyyy-MM-dd-HH:mm:ss.SSS" ).format( new Date() );
        for ( Map.Entry<String, String> entry : resources.entrySet() )
        {
            String curRes = entry.getKey();
            String curDest = entry.getValue();
            InputStream resourceStream = null;
            resourceStream = getClass().getResourceAsStream( curRes );
            if ( null == resourceStream )
            {
                try
                { 
                    resourceStream = new FileInputStream( new File( curRes ) );
                }
                catch ( Exception e )
                {
                    // handled later
                    resourceStream = null;
                }
            }
            
            getLog().debug( "resource stream to open : " + curRes );
            getLog().debug( "destfile pattern : " + curDest );
            if ( null != resourceStream )
            {
                String sRelativePath = curDest.replaceFirst( Pattern.quote( archetypeArtifactId
                    + File.separator ), "" );
                File newFile = new File( basedir + File.separator + sRelativePath );
                
//3/ create empty dir struct; if needed using a descriptor 
//create all non exists folders
                File newDirs = new File( newFile.getParent() );
                if ( Files.notExists( Paths.get( newDirs.getPath() ) ) )
                {
                    getLog().info( "dirs to generate : " + newDirs.getAbsoluteFile() );
                    newDirs.mkdirs();
                }
                
                if ( !newFile.getName().equals( "empty.dir" ) )
                {
                    getLog().info( "file to generate : " + newFile.getAbsoluteFile() );
                    try
                    { 
                        if ( !newFile.createNewFile() )
                        {
                            // duplicate existing file
                            FileInputStream inStream = new FileInputStream( newFile );
                            File backFile = File.createTempFile( newFile.getName() + ".",
                                "." + sExecutionDate + ".back", newFile.getParentFile() );
                            FileOutputStream outStream = new FileOutputStream( backFile );
                            
                            IOUtils.copy( inStream, outStream );
                            // manage file times
                            //backFile.setLastModified(newFile.lastModified());
                            BasicFileAttributes attributesFrom = 
                                Files.getFileAttributeView( Paths.get( newFile.getPath() ),
                                    BasicFileAttributeView.class ).readAttributes();
                            BasicFileAttributeView attributesToView =
                                Files.getFileAttributeView( Paths.get( backFile.getPath() ),
                                    BasicFileAttributeView.class );
                            attributesToView.setTimes(
                                attributesFrom.lastModifiedTime(),
                                attributesFrom.lastAccessTime(),
                                attributesFrom.creationTime() );

                            inStream.close();
                            outStream.close();
                        }
                        FileOutputStream outStream = new FileOutputStream( newFile );
                        
//4/ variable substitution :
// change prefix and suffix to '$(' and ')'
// see https://commons.apache.org/proper/commons-lang/javadocs/api-2.6/org/apache/commons/lang/text/StrSubstitutor.html
                        String content = IOUtils.toString( resourceStream, "UTF8" );
                        content = substitutor.replace( content );

                        //IOUtils.copy( resourceStream, outStream );
                        IOUtils.write( content, outStream, "UTF8" );

                        outStream.close();
                        resourceStream.close();
                    }
                    catch ( IOException e )
                    {
                        getLog().error( "File " + newFile.getAbsoluteFile() + " can't be created : " + e );
                    }
                }
            }
            else
            {
                getLog().error( "Unable to open resource " + curRes );
            }            
        }
    }
}
