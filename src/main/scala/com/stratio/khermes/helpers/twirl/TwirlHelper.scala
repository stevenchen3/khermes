/**
 * © 2017 Stratio Big Data Inc., Sucursal en España.
 *
 * This software is licensed under the Apache 2.0.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the terms of the License for more details.
 *
 * SPDX-License-Identifier:  Apache-2.0.
 */
package com.stratio.khermes.helpers.twirl

import java.io.File
import java.lang.reflect.Method
import java.math.BigInteger
import java.net.{URL, URLClassLoader}
import java.security.{CodeSource, MessageDigest}

import com.stratio.khermes.commons.constants.AppConstants
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import play.twirl.compiler.{GeneratedSource, TwirlCompiler}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.reflect.internal.util.Position
import scala.tools.nsc.reporters.ConsoleReporter
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.io.{File ⇒ ScalaFile}

/**
 * Helper used to parse and compile templates using Twirl.
 */
object TwirlHelper extends LazyLogging {

  /**
   * Compiles and executes a template with Twirl. It follows the next steps:
   * Step 1) A string that represents the template is saved in Khermes' templates path.
   * Step 2) The engine generates a scala files to be compiled.
   * Step 3) The engine compiles the scala files generated in the previous step.
   * Step 4) Finally it executes the compiled files interpolating values with the template.
   * @param templateContent a string represents the content of the given template.
   * @param templateName the name of the file that will contain the content of the template.
   * @param config with Khermes' configuration.
   * @tparam T with the type of object to inject in the template.
   * @return a compiled and executed template.
   */
  def template[T](
    templateContent: String,
    templateName: String,
    compilationId: String = "default"
  )(implicit config: Config): CompiledTemplate[T] = {
    import AppConstants._

    val templatesPath = config.getString("khermes.templates-path")
    outputTemplateToDisk(templateContent, templateName, templatesPath)

    // Where twirl template source files (e.g., foo.scala.html) reside
    val sourceDirectory = new File(templatesPath)
    // Where generated Scala files (e.g., foo.template.scala) reside
    val generatedDirectory =
      new File(s"$templatesPath/${GeneratedTemplatesPrefix}/${compilationId}") // *.scala
    // Where *.class files (e.g., after compiling foo.template.scala) reside
    val generatedClasses =
      new File(s"$templatesPath/${GeneratedClassesPrefix}/${compilationId}")   // *.class

    deleteRecursively(generatedDirectory)
    deleteRecursively(generatedClasses)
    generatedClasses.mkdirs()
    generatedDirectory.mkdirs()

    val helper = CompilerHelper(sourceDirectory, generatedDirectory, generatedClasses)
    val compiledTemplate = helper.compile[T](
      s"$templateName.scala.html",
      s"html.$templateName",
      Seq("com.stratio.khermes.helpers.faker.Faker")
    )
    compiledTemplate
  }

  private[this] def outputTemplateToDisk(content: String, name: String, paths: String): Unit = {
    val filename = s"${paths}/${name}.scala.html"
    val file     = ScalaFile(filename)
    if (file.exists) {
      if (sha256(content) != sha256(Source.fromFile(filename).mkString)) file.writeAll(content)
    } else file.writeAll(content)
  }

  protected[this] def sha256(s: String): String = {
    val hash = new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")))
    String.format("%032x", hash)
  }

  /**
   * If the template is wrong this exception informs about the mistake.
   * @param message with information about the error.
   * @param line that contains the error.
   * @param column that contains the error.
   */
  case class CompilationError(
    message: String,
    line: Int,
    column: Int
  ) extends RuntimeException(message)

  /**
   * Deletes all content in a path.
   * @param dir a file that represents the path to delete.
   */
  protected[this] def deleteRecursively(dir: File) {
    if(dir.isDirectory) dir.listFiles().foreach(deleteRecursively)
    dir.delete()
  }

  /**
   * Helper used to compile templates internally.
   * @param sourceDirectory that contains original templates.
   * @param generatedDirectory that contains scala files from the templates.
   * @param generatedClasses that contains class files with the result of the compilation.
   */
  protected[this] class CompilerHelper(
    sourceDirectory: File,
    generatedDirectory: File,
    generatedClasses: File
  ) {
    private[this] val twirlCompilerClassName = "play.twirl.compiler.TwirlCompiler"

    val twirlCompiler: TwirlCompiler.type = TwirlCompiler
    val classloader: URLClassLoader = new URLClassLoader(
      Array(generatedClasses.toURI.toURL),
      Class.forName(twirlCompilerClassName).getClassLoader
    )
    val compileErrors: ListBuffer[CompilationError] = new mutable.ListBuffer[CompilationError]

    // Scala compiler object
    val compiler: Global = {
      def additionalClassPathEntry: Option[String] = Some {
        Class.forName(twirlCompilerClassName)
          .getClassLoader.asInstanceOf[URLClassLoader]
          .getURLs.map(url ⇒ new File(url.toURI)).mkString(":")
      }

      val settings: Settings = new Settings
      val scalaObjectSource: CodeSource = Class.forName("scala.Option")
        .getProtectionDomain
        .getCodeSource

      val compilerPath: URL = Class.forName("scala.tools.nsc.Interpreter")
        .getProtectionDomain
        .getCodeSource
        .getLocation

      val libPath: URL = scalaObjectSource.getLocation
      val pathList: List[URL] = List(compilerPath, libPath)
      val originalBootClasspath: String = settings.bootclasspath.value
      settings.bootclasspath.value = {
        (originalBootClasspath :: pathList) ::: additionalClassPathEntry.toList
      } mkString File.pathSeparator
      settings.outdir.value = generatedClasses.getAbsolutePath

      new Global(settings, new ConsoleReporter(settings) {
        override def printMessage(position: Position, message: String) = {
          compileErrors.append(CompilationError(message, position.line, position.point))
        }
      })
    }

    def compile[T](
      templateName: String,
      className: String,
      additionalImports: Seq[String] = Nil
    ): CompiledTemplate[T] = {
      val templateFile = new File(sourceDirectory, templateName)
      val Some(generated) = twirlCompiler.compile(
        templateFile,
        sourceDirectory,
        generatedDirectory,
        "play.twirl.api.TxtFormat",
        additionalImports = TwirlCompiler.DefaultImports ++ additionalImports
      )
      val mapper = GeneratedSource(generated)
      val run = new compiler.Run
      compileErrors.clear()
      run.compile(List(generated.getAbsolutePath))

      compileErrors.headOption.foreach {
        case CompilationError(msg, line, column) ⇒
          compileErrors.clear()
          throw CompilationError(msg, mapper.mapLine(line), mapper.mapPosition(column))
      }
      new CompiledTemplate[T](className, classloader)
    }
  }

  protected[this] object CompilerHelper {
    def apply(
      sourceDirectory:    File,
      generatedDirectory: File,
      generatedClasses:   File
    ): CompilerHelper = new CompilerHelper(sourceDirectory, generatedDirectory, generatedClasses)
  }

  /**
   * From a classname and a classloader it returns a result of a compiled and executed template.
   * @param className with the classname.
   * @param classloader with the classloader.
   * @tparam T with the type of object to inject in the template.
   */
  class CompiledTemplate[T](className: String, classloader: URLClassLoader) {
    var method: Option[Method] = None
    var declaredField: Option[AnyRef] = None

    private def getF(template: Any) = {
      if(method.isEmpty) {
        method = Option(template.getClass.getMethod("f"))
        method.get.invoke(template).asInstanceOf[T]
      } else {
        method.get.invoke(template).asInstanceOf[T]
      }
    }
    /**
     * @return the result of a compiled and executed template.
     */
    //scalastyle:off
    def static: T = {
      if(declaredField.isEmpty) {
        declaredField = Option {
          classloader.loadClass(className + "$").getDeclaredField("MODULE$").get(null)
        }
        getF(declaredField.get)
      } else {
        getF(declaredField.get)
      }
    }
    //scalastyle:on
  }
}
