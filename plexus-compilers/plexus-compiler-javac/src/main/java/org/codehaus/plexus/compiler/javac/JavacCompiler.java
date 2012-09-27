package org.codehaus.plexus.compiler.javac;

/**
 * The MIT License
 *
 * Copyright (c) 2005, The Codehaus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 *
 * Copyright 2004 The Apache Software Foundation
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import org.codehaus.plexus.compiler.AbstractCompiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerError;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Os;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.StringTokenizer;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 * @author <a href="mailto:matthew.pocock@ncl.ac.uk">Matthew Pocock</a>
 * @author <a href="mailto:joerg.wassmer@web.de">J&ouml;rg Wa&szlig;mer</a>
 * @author Others
 * @version $Id$
 * @plexus.component role="org.codehaus.plexus.compiler.Compiler"
 * role-hint="javac"
 */
public class JavacCompiler
    extends AbstractCompiler
{

    // see compiler.warn.warning in compiler.properties of javac sources
    private static final String[] WARNING_PREFIXES = { "warning: ", "\u8b66\u544a: ", "\u8b66\u544a\uff1a " };

    // see compiler.note.note in compiler.properties of javac sources
    private static final String[] NOTE_PREFIXES = { "Note: ", "\u6ce8: ", "\u6ce8\u610f\uff1a " };

    // ----------------------------------------------------------------------
    //
    // ----------------------------------------------------------------------

    public JavacCompiler()
    {
        super( CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE, ".java", ".class", null );
    }

    // ----------------------------------------------------------------------
    // Compiler Implementation
    // ----------------------------------------------------------------------

    public List<CompilerError> compile( CompilerConfiguration config )
        throws CompilerException
    {
        File destinationDir = new File( config.getOutputLocation() );

        if ( !destinationDir.exists() )
        {
            destinationDir.mkdirs();
        }

        String[] sourceFiles = getSourceFiles( config );

        if ( ( sourceFiles == null ) || ( sourceFiles.length == 0 ) )
        {
            return Collections.emptyList();
        }

        if ( ( getLogger() != null ) && getLogger().isInfoEnabled() )
        {
            getLogger().info( "Compiling " + sourceFiles.length + " " +
                                  "source file" + ( sourceFiles.length == 1 ? "" : "s" ) +
                                  " to " + destinationDir.getAbsolutePath() );
        }

        String[] args = buildCompilerArguments( config, sourceFiles );

        List<CompilerError> messages;

        if ( config.isFork() )
        {
            String executable = config.getExecutable();

            if ( StringUtils.isEmpty( executable ) )
            {
                try
                {
                    executable = getJavacExecutable();
                }
                catch ( IOException e )
                {
                    getLogger().warn( "Unable to autodetect 'javac' path, using 'javac' from the environment." );
                    executable = "javac";
                }
            }

            messages = compileOutOfProcess( config, executable, args );
        }
        else
        {
            messages = compileInProcess( args, config, sourceFiles );
        }

        return messages;
    }

    public String[] createCommandLine( CompilerConfiguration config )
        throws CompilerException
    {
        return buildCompilerArguments( config, getSourceFiles( config ) );
    }

    public static String[] buildCompilerArguments( CompilerConfiguration config, String[] sourceFiles )
    {
        List<String> args = new ArrayList<String>();

        // ----------------------------------------------------------------------
        // Set output
        // ----------------------------------------------------------------------

        File destinationDir = new File( config.getOutputLocation() );

        args.add( "-d" );

        args.add( destinationDir.getAbsolutePath() );

        // ----------------------------------------------------------------------
        // Set the class and source paths
        // ----------------------------------------------------------------------

        List<String> classpathEntries = config.getClasspathEntries();
        if ( classpathEntries != null && !classpathEntries.isEmpty() )
        {
            args.add( "-classpath" );

            args.add( getPathString( classpathEntries ) );
        }

        List<String> sourceLocations = config.getSourceLocations();
        if ( sourceLocations != null && !sourceLocations.isEmpty() )
        {
            //always pass source path, even if sourceFiles are declared,
            //needed for jsr269 annotation processing, see MCOMPILER-98
            args.add( "-sourcepath" );

            args.add( getPathString( sourceLocations ) );
        }

        if ( config.isFork() )
        {
            args.addAll( Arrays.asList( sourceFiles ) );
        }

        if ( !isPreJava16( config ) )
        {
            //now add jdk 1.6 annotation processing related parameters

            if ( config.getGeneratedSourcesDirectory() != null )
            {
                config.getGeneratedSourcesDirectory().mkdirs();

                args.add( "-s" );
                args.add( config.getGeneratedSourcesDirectory().getAbsolutePath() );
            }
            if ( config.getProc() != null )
            {
                args.add( "-proc:" + config.getProc() );
            }
            if ( config.getAnnotationProcessors() != null )
            {
                args.add( "-processor" );
                String[] procs = config.getAnnotationProcessors();
                StringBuilder buffer = new StringBuilder();
                for ( int i = 0; i < procs.length; i++ )
                {
                    if ( i > 0 )
                    {
                        buffer.append( "," );
                    }

                    buffer.append( procs[i] );
                }
                args.add( buffer.toString() );
            }
        }

        if ( config.isOptimize() )
        {
            args.add( "-O" );
        }

        if ( config.isDebug() )
        {
            if ( StringUtils.isNotEmpty( config.getDebugLevel() ) )
            {
                args.add( "-g:" + config.getDebugLevel() );
            }
            else
            {
                args.add( "-g" );
            }
        }

        if ( config.isVerbose() )
        {
            args.add( "-verbose" );
        }

        if ( config.isShowDeprecation() )
        {
            args.add( "-deprecation" );

            // This is required to actually display the deprecation messages
            config.setShowWarnings( true );
        }

        if ( !config.isShowWarnings() )
        {
            args.add( "-nowarn" );
        }

        // TODO: this could be much improved
        if ( StringUtils.isEmpty( config.getTargetVersion() ) )
        {
            // Required, or it defaults to the target of your JDK (eg 1.5)
            args.add( "-target" );
            args.add( "1.1" );
        }
        else
        {
            args.add( "-target" );
            args.add( config.getTargetVersion() );
        }

        if ( !suppressSource( config ) && StringUtils.isEmpty( config.getSourceVersion() ) )
        {
            // If omitted, later JDKs complain about a 1.1 target
            args.add( "-source" );
            args.add( "1.3" );
        }
        else if ( !suppressSource( config ) )
        {
            args.add( "-source" );
            args.add( config.getSourceVersion() );
        }

        if ( !suppressEncoding( config ) && !StringUtils.isEmpty( config.getSourceEncoding() ) )
        {
            args.add( "-encoding" );
            args.add( config.getSourceEncoding() );
        }

        for ( Map.Entry<String, String> entry : config.getCustomCompilerArgumentsAsMap().entrySet() )
        {
            String key = entry.getKey();

            if ( StringUtils.isEmpty( key ) || key.startsWith( "-J" ) )
            {
                continue;
            }

            args.add( key );

            String value = entry.getValue();

            if ( StringUtils.isEmpty( value ) )
            {
                continue;
            }

            args.add( value );
        }

        return args.toArray( new String[args.size()] );
    }

    /**
     * Determine if the compiler is a version prior to 1.4.
     * This is needed as 1.3 and earlier did not support -source or -encoding parameters
     *
     * @param config The compiler configuration to test.
     * @return true if the compiler configuration represents a Java 1.4 compiler or later, false otherwise
     */
    private static boolean isPreJava14( CompilerConfiguration config )
    {
        String v = config.getCompilerVersion();

        if ( v == null )
        {
            return false;
        }

        return v.startsWith( "1.3" ) || v.startsWith( "1.2" ) || v.startsWith( "1.1" ) || v.startsWith( "1.0" );
    }

    /**
     * Determine if the compiler is a version prior to 1.6.
     * This is needed for annotation processing parameters.
     *
     * @param config The compiler configuration to test.
     * @return true if the compiler configuration represents a Java 1.6 compiler or later, false otherwise
     */
    private static boolean isPreJava16( CompilerConfiguration config )
    {
        String v = config.getCompilerVersion();

        if ( v == null )
        {
            //mkleint: i haven't completely understood the reason for the
            //compiler version parameter, checking source as well, as most projects will have this one set, not the compiler
            String s = config.getSourceVersion();
            if ( s == null )
            {
                //now return true, as the 1.6 version is not the default - 1.4 is.
                return true;
            }
            return s.startsWith( "1.5" ) || s.startsWith( "1.4" ) || s.startsWith( "1.3" ) || s.startsWith( "1.2" )
                || s.startsWith( "1.1" ) || s.startsWith( "1.0" );
        }

        return v.startsWith( "1.5" ) || v.startsWith( "1.4" ) || v.startsWith( "1.3" ) || v.startsWith( "1.2" )
            || v.startsWith( "1.1" ) || v.startsWith( "1.0" );
    }


    private static boolean suppressSource( CompilerConfiguration config )
    {
        return isPreJava14( config );
    }

    private static boolean suppressEncoding( CompilerConfiguration config )
    {
        return isPreJava14( config );
    }

    /**
     * Compile the java sources in a external process, calling an external executable,
     * like javac.
     *
     * @param config     compiler configuration
     * @param executable name of the executable to launch
     * @param args       arguments for the executable launched
     * @return List of CompilerError objects with the errors encountered.
     * @throws CompilerException
     */
    List<CompilerError> compileOutOfProcess( CompilerConfiguration config, String executable, String[] args )
        throws CompilerException
    {
        Commandline cli = new Commandline();

        cli.setWorkingDirectory( config.getWorkingDirectory().getAbsolutePath() );

        cli.setExecutable( executable );

        try
        {
            File argumentsFile = createFileWithArguments( args, config.getOutputLocation() );
            cli.addArguments(
                new String[]{ "@" + argumentsFile.getCanonicalPath().replace( File.separatorChar, '/' ) } );

            if ( !StringUtils.isEmpty( config.getMaxmem() ) )
            {
                cli.addArguments( new String[]{ "-J-Xmx" + config.getMaxmem() } );
            }

            if ( !StringUtils.isEmpty( config.getMeminitial() ) )
            {
                cli.addArguments( new String[]{ "-J-Xms" + config.getMeminitial() } );
            }

            for ( String key : config.getCustomCompilerArgumentsAsMap().keySet() )
            {
                if ( StringUtils.isNotEmpty( key ) && key.startsWith( "-J" ) )
                {
                    cli.addArguments( new String[]{ key } );
                }
            }
        }
        catch ( IOException e )
        {
            throw new CompilerException( "Error creating file with javac arguments", e );
        }

        CommandLineUtils.StringStreamConsumer out = new CommandLineUtils.StringStreamConsumer();

        CommandLineUtils.StringStreamConsumer err = new CommandLineUtils.StringStreamConsumer();

        int returnCode;

        List<CompilerError> messages;

        if ( ( getLogger() != null ) && getLogger().isDebugEnabled() )
        {
            File commandLineFile =
                new File( config.getOutputLocation(), "javac." + ( Os.isFamily( Os.FAMILY_WINDOWS ) ? "bat" : "sh" ) );
            try
            {
                FileUtils.fileWrite( commandLineFile.getAbsolutePath(), cli.toString().replaceAll( "'", "" ) );

                if ( !Os.isFamily( Os.FAMILY_WINDOWS ) )
                {
                    Runtime.getRuntime().exec( new String[]{ "chmod", "a+x", commandLineFile.getAbsolutePath() } );
                }
            }
            catch ( IOException e )
            {
                if ( ( getLogger() != null ) && getLogger().isWarnEnabled() )
                {
                    getLogger().warn( "Unable to write '" + commandLineFile.getName() + "' debug script file", e );
                }
            }
        }

        try
        {
            returnCode = CommandLineUtils.executeCommandLine( cli, out, err );

            messages = parseModernStream( returnCode, new BufferedReader( new StringReader( err.getOutput() ) ) );
        }
        catch ( CommandLineException e )
        {
            throw new CompilerException( "Error while executing the external compiler.", e );
        }
        catch ( IOException e )
        {
            throw new CompilerException( "Error while executing the external compiler.", e );
        }

        if ( ( returnCode != 0 ) && messages.isEmpty() )
        {
            if ( err.getOutput().length() == 0 )
            {
                throw new CompilerException(
                    "Unknown error trying to execute the external compiler: " + EOL + cli.toString() );
            }
            else
            {
                messages.add( new CompilerError(
                    "Failure executing javac,  but could not parse the error:" + EOL + err.getOutput(), true ) );
            }
        }

        return messages;
    }

    /**
     * Compile the java sources in the current JVM, without calling an external executable,
     * using <code>com.sun.tools.javac.Main</code> class
     *
     *
     * @param args arguments for the compiler as they would be used in the command line javac
     * @param sourceFiles
     * @return List of CompilerError objects with the errors encountered.
     * @throws CompilerException
     */
    List<CompilerError> compileInProcess( String[] args, final CompilerConfiguration config, String[] sourceFiles )
        throws CompilerException
    {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if ( compiler == null )
        {
            return Collections.singletonList( new CompilerError( "No compiler is provided in this environment.  Perhaps you are running on a JRE rather than a JDK?" ) );
        }
        final String sourceEncoding = config.getSourceEncoding();
        final Charset sourceCharset = sourceEncoding == null ? null : Charset.forName( sourceEncoding );
        final DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<JavaFileObject>();
        final StandardJavaFileManager standardFileManager = compiler.getStandardFileManager( collector, null, sourceCharset );

        final Iterable<? extends JavaFileObject> fileObjects = standardFileManager.getJavaFileObjectsFromStrings( Arrays.asList( sourceFiles ) );
        final JavaCompiler.CompilationTask task = compiler.getTask( null, standardFileManager, collector, Arrays.asList( args ), null, fileObjects );
        final Boolean result = task.call();
        final ArrayList<CompilerError> compilerErrors = new ArrayList<CompilerError>();
        for ( Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics() )
        {
            Diagnostic.Kind kind;
            switch ( diagnostic.getKind() )
            {
                case ERROR:
                    kind = Diagnostic.Kind.ERROR;
                    break;
                case WARNING:
                    kind = Diagnostic.Kind.WARNING;
                    break;
                case MANDATORY_WARNING:
                    kind = Diagnostic.Kind.MANDATORY_WARNING;
                    break;
                case NOTE:
                    kind = Diagnostic.Kind.NOTE;
                    break;
                default:
                    kind = Diagnostic.Kind.OTHER;
                    break;
            }
            String baseMessage = diagnostic.getMessage( null );
            if (baseMessage == null) {
                continue;
            }
            JavaFileObject source = diagnostic.getSource();
            String longFileName = source == null ? null : source.toUri().getPath();
            String shortFileName = source == null ? null : source.getName();
            String formattedMessage = baseMessage;
            int lineNumber = Math.max(0, (int) diagnostic.getLineNumber());
            int columnNumber = Math.max( 0, (int) diagnostic.getColumnNumber() );
            if ( source != null && lineNumber > 0 )
            {
                // Some compilers like to copy the file name into the message, which makes it appear twice.
                String possibleTrimming = longFileName + ":" + lineNumber + ": ";
                if ( formattedMessage.startsWith( possibleTrimming ))
                {
                    formattedMessage = formattedMessage.substring( possibleTrimming.length() );
                }
                else
                {
                    possibleTrimming = shortFileName + ":" + lineNumber + ": ";
                    if (formattedMessage.startsWith( possibleTrimming ))
                    {
                        formattedMessage = formattedMessage.substring( possibleTrimming.length() );
                    }
                }
            }
            compilerErrors.add( new CompilerError( longFileName, kind, lineNumber, columnNumber, lineNumber, columnNumber, formattedMessage ) );
        }
        if ( result != Boolean.TRUE && compilerErrors.isEmpty() )
        {
            compilerErrors.add( new CompilerError( "An unknown compilation problem occurred", Diagnostic.Kind.ERROR ) );
        }
        return compilerErrors;
    }

    /**
     * Parse the output from the compiler into a list of CompilerError objects
     *
     * @param exitCode The exit code of javac.
     * @param input    The output of the compiler
     * @return List of CompilerError objects
     * @throws IOException
     */
    static List<CompilerError> parseModernStream( int exitCode, BufferedReader input )
        throws IOException
    {
        List<CompilerError> errors = new ArrayList<CompilerError>();

        String line;

        StringBuilder buffer;

        while ( true )
        {
            // cleanup the buffer
            buffer = new StringBuilder(); // this is quicker than clearing it

            // most errors terminate with the '^' char
            do
            {
                line = input.readLine();

                if ( line == null )
                {
                    return errors;
                }

                // TODO: there should be a better way to parse these
                if ( ( buffer.length() == 0 ) && line.startsWith( "error: " ) )
                {
                    errors.add( new CompilerError( line, true ) );
                }
                else if ( ( buffer.length() == 0 ) && isNote( line ) )
                {
                    // skip, JDK 1.5 telling us deprecated APIs are used but -Xlint:deprecation isn't set
                }
                else
                {
                    buffer.append( line );

                    buffer.append( EOL );
                }
            }
            while ( !line.endsWith( "^" ) );

            // add the error bean
            errors.add( parseModernError( exitCode, buffer.toString() ) );
        }
    }

    private static boolean isNote( String line )
    {
        for ( int i = 0; i < NOTE_PREFIXES.length; i++ )
        {
            if ( line.startsWith( NOTE_PREFIXES[i] ) )
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Construct a CompilerError object from a line of the compiler output
     *
     * @param exitCode The exit code from javac.
     * @param error    output line from the compiler
     * @return the CompilerError object
     */
    static CompilerError parseModernError( int exitCode, String error )
    {
        StringTokenizer tokens = new StringTokenizer( error, ":" );

        boolean isError = exitCode != 0;

        StringBuilder msgBuffer;

        try
        {
            // With Java 6 error output lines from the compiler got longer. For backward compatibility
            // .. and the time being, we eat up all (if any) tokens up to the erroneous file and source
            // .. line indicator tokens.

            boolean tokenIsAnInteger;

            String file = null;

            String currentToken = null;

            do
            {
                if ( currentToken != null )
                {
                    if ( file == null )
                    {
                        file = currentToken;
                    }
                    else
                    {
                        file = file + ':' + currentToken;
                    }
                }

                currentToken = tokens.nextToken();

                // Probably the only backward compatible means of checking if a string is an integer.

                tokenIsAnInteger = true;

                try
                {
                    Integer.parseInt( currentToken );
                }
                catch ( NumberFormatException e )
                {
                    tokenIsAnInteger = false;
                }
            }
            while ( !tokenIsAnInteger );

            String lineIndicator = currentToken;

            int startOfFileName = file.lastIndexOf( ']' );

            if ( startOfFileName > -1 )
            {
                file = file.substring( startOfFileName + 2 );
            }

            int line = Integer.parseInt( lineIndicator );

            msgBuffer = new StringBuilder();

            String msg = tokens.nextToken( EOL ).substring( 2 );

            // Remove the 'warning: ' prefix
            String warnPrefix = getWarnPrefix( msg );
            if ( warnPrefix != null )
            {
                isError = false;
                msg = msg.substring( warnPrefix.length() );
            }
            else
            {
                isError = exitCode != 0;
            }

            msgBuffer.append( msg );

            msgBuffer.append( EOL );

            String context = tokens.nextToken( EOL );

            String pointer = tokens.nextToken( EOL );

            if ( tokens.hasMoreTokens() )
            {
                msgBuffer.append( context );    // 'symbol' line

                msgBuffer.append( EOL );

                msgBuffer.append( pointer );    // 'location' line

                msgBuffer.append( EOL );

                context = tokens.nextToken( EOL );

                try
                {
                    pointer = tokens.nextToken( EOL );
                }
                catch ( NoSuchElementException e )
                {
                    pointer = context;

                    context = null;
                }

            }

            String message = msgBuffer.toString();

            int startcolumn = pointer.indexOf( "^" );

            int endcolumn = context == null ? startcolumn : context.indexOf( " ", startcolumn );

            if ( endcolumn == -1 )
            {
                endcolumn = context.length();
            }

            return new CompilerError( file, isError, line, startcolumn, line, endcolumn, message.trim() );
        }
        catch ( NoSuchElementException e )
        {
            return new CompilerError( "no more tokens - could not parse error message: " + error, isError );
        }
        catch ( NumberFormatException e )
        {
            return new CompilerError( "could not parse error message: " + error, isError );
        }
        catch ( Exception e )
        {
            return new CompilerError( "could not parse error message: " + error, isError );
        }
    }

    private static String getWarnPrefix( String msg )
    {
        for ( int i = 0; i < WARNING_PREFIXES.length; i++ )
        {
            if ( msg.startsWith( WARNING_PREFIXES[i] ) )
            {
                return WARNING_PREFIXES[i];
            }
        }
        return null;
    }

    /**
     * put args into a temp file to be referenced using the @ option in javac command line
     *
     * @param args
     * @return the temporary file wth the arguments
     * @throws IOException
     */
    private File createFileWithArguments( String[] args, String outputDirectory )
        throws IOException
    {
        PrintWriter writer = null;
        try
        {
            File tempFile;
            if ( ( getLogger() != null ) && getLogger().isDebugEnabled() )
            {
                tempFile =
                    File.createTempFile( JavacCompiler.class.getName(), "arguments", new File( outputDirectory ) );
            }
            else
            {
                tempFile = File.createTempFile( JavacCompiler.class.getName(), "arguments" );
                tempFile.deleteOnExit();
            }

            writer = new PrintWriter( new FileWriter( tempFile ) );

            for ( int i = 0; i < args.length; i++ )
            {
                String argValue = args[i].replace( File.separatorChar, '/' );

                writer.write( "\"" + argValue + "\"" );

                writer.println();
            }

            writer.flush();

            return tempFile;

        }
        finally
        {
            if ( writer != null )
            {
                writer.close();
            }
        }
    }

    /**
     * Get the path of the javac tool executable: try to find it depending the OS or the <code>java.home</code>
     * system property or the <code>JAVA_HOME</code> environment variable.
     *
     * @return the path of the Javadoc tool
     * @throws IOException if not found
     */
    private static String getJavacExecutable()
        throws IOException
    {
        String javacCommand = "javac" + ( Os.isFamily( Os.FAMILY_WINDOWS ) ? ".exe" : "" );

        String javaHome = System.getProperty( "java.home" );
        File javacExe;
        if ( Os.isName( "AIX" ) )
        {
            javacExe = new File( javaHome + File.separator + ".." + File.separator + "sh", javacCommand );
        }
        else if ( Os.isName( "Mac OS X" ) )
        {
            javacExe = new File( javaHome + File.separator + "bin", javacCommand );
        }
        else
        {
            javacExe = new File( javaHome + File.separator + ".." + File.separator + "bin", javacCommand );
        }

        // ----------------------------------------------------------------------
        // Try to find javacExe from JAVA_HOME environment variable
        // ----------------------------------------------------------------------
        if ( !javacExe.isFile() )
        {
            Properties env = CommandLineUtils.getSystemEnvVars();
            javaHome = env.getProperty( "JAVA_HOME" );
            if ( StringUtils.isEmpty( javaHome ) )
            {
                throw new IOException( "The environment variable JAVA_HOME is not correctly set." );
            }
            if ( !new File( javaHome ).isDirectory() )
            {
                throw new IOException(
                    "The environment variable JAVA_HOME=" + javaHome + " doesn't exist or is not a valid directory." );
            }

            javacExe = new File( env.getProperty( "JAVA_HOME" ) + File.separator + "bin", javacCommand );
        }

        if ( !javacExe.isFile() )
        {
            throw new IOException( "The javadoc executable '" + javacExe
                                       + "' doesn't exist or is not a file. Verify the JAVA_HOME environment variable." );
        }

        return javacExe.getAbsolutePath();
    }


}
