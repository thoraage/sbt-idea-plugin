/**
 * Copyright (C) 2010, Mikko Peltonen, Jon-Anders Teigen, Michal Příhoda, Graham Tackley, Ismael Juma, Odd Möller
 * Licensed under the new BSD License.
 * See the LICENSE file for details.
 */

import sbt._
import java.io.File
import xml.{UnprefixedAttribute, NodeSeq, Node, NodeBuffer}

class IdeaModuleDescriptor(val project: BasicDependencyProject, val log: Logger) extends SaveableXml with ProjectPaths {
  val path = String.format("%s/%s.iml", projectPath, project.name)

  def content: Node = {
    <module type="JAVA_MODULE" version="4">
      <component name="FacetManager">
        <facet type="scala" name="Scala">
          <configuration>
            <option name="compilerLibraryLevel" value="Project" />
            <option name="compilerLibraryName" value="buildScala" />
          </configuration>
        </facet>
        {
          project match {
            case webProject: DefaultWebProject => webFacet(webProject)
            case _ => scala.xml.Null
          }
        }
      </component>
      <component name="NewModuleRootManager" inherit-compiler-output="false">
        <output url={"file://$MODULE_DIR$/" + project.asInstanceOf[ScalaPaths].mainCompilePath.relativePath.toString} />
        <output-test url={"file://$MODULE_DIR$/" + project.asInstanceOf[ScalaPaths].testCompilePath.relativePath.toString} />
        <exclude-output />
        <content url="file://$MODULE_DIR$"> <!--{
            val paths = project.asInstanceOf[ScalaPaths]
            project.
            def collect(paths: List[PathFinder], isTestFolder: Boolean) = {
              val nodes = new NodeBuffer()
              paths.foldRight(Path.emptyPathFinder)(_ +++ _)/*.filter(_.exists)*/.getRelativePaths.map { nodes &+ sourceFolder(_, isTestFolder)}
              nodes
            }
            collect(paths.mainSourceRoots :: Nil, false) //&+
                    //collect(paths.testSourceRoots :: Nil, true)
          }-->
          { nodePerExistingSourceFolder("src_managed/main/scala" :: "src/main/scala" :: "src/main/resources" :: "src/main/java" :: "src/it/scala" :: Nil) }
          { nodePerExistingTestSourceFolder("src/test/scala" :: "src/test/resources" :: "src/test/java" :: Nil) }
          <excludeFolder url="file://$MODULE_DIR$/target" />
        </content>
        {
          project match {
            case sp: ScalaPaths =>
              val nodeBuffer = new xml.NodeBuffer
              if (sp.testResources.getFiles.exists(_.exists))
                nodeBuffer &+ moduleLibrary(Some("TEST"), None, None,
                  Some("file://$MODULE_DIR$/" + relativePath(sp.testResourcesOutputPath.asFile)), false)
              if (sp.mainResources.getFiles.exists(_.exists))
                nodeBuffer &+ moduleLibrary(None, None, None,
                  Some("file://$MODULE_DIR$/" + relativePath(sp.mainResourcesOutputPath.asFile)), false)
              nodeBuffer
            case _ => xml.Null
          }
        }
        <orderEntry type="inheritedJdk"/>
        <orderEntry type="sourceFolder" forTests="false"/>
        <orderEntry type="library" name="buildScala" level="project"/>
        {
          def isDependencyProject(p: Project) = p != project && !p.isInstanceOf[ParentProject]
          project.projectClosure.filter(isDependencyProject).map { dep =>
            log.info("Project dependency: " + dep.name)
            <orderEntry type="module" module-name={dep.name} />
          }
        }
        {
          val Jar = ".jar"
          val Jars = GlobFilter("*" + Jar)

          val SourcesJar = "-sources" + Jar
          val Sources = GlobFilter("*" + SourcesJar)

          val JavaDocJar = "-javadoc" + Jar
          val JavaDocs = GlobFilter("*" + JavaDocJar)

          val jars = ideClasspath ** Jars

          val sources = jars ** Sources
          val javadoc = jars ** JavaDocs
          val classes = jars --- sources --- javadoc

          def cut(name: String, c: String) = name.substring(0, name.length - c.length)
          def named(pf: PathFinder, suffix: String) = Map() ++ pf.getFiles.map(relativePath _).map(path => (cut(path, suffix), path))

          val namedSources = named(sources, SourcesJar)
          val namedJavadoc = named(javadoc, JavaDocJar)
          val namedClasses = named(classes, Jar)

          val defaultJars = defaultClasspath ** Jars
          val testJars = testClasspath ** Jars
          val runtimeJars = runtimeClasspath ** Jars
          val providedJars = providedClasspath ** Jars

          val defaultScope = named(defaultJars, Jar)
          val testScope = named(testJars, Jar)
          val runtimeScope = named(runtimeJars, Jar)
          val providedScope = named(providedJars, Jar)

          def scope(name: String) = {
            if (testScope.contains(name))
              Some("TEST")
            else if (runtimeScope.contains(name))
              Some("RUNTIME")
            else if (providedScope.contains(name))
              Some("PROVIDED")
            else
              None //default
          }

          val names = namedSources.keySet ++ namedJavadoc.keySet ++ namedClasses.keySet

          val libs = new scala.xml.NodeBuffer
          names.foreach {
            name =>
              libs &+ moduleLibrary(scope(name), namedSources.get(name), namedJavadoc.get(name), namedClasses.get(name), true)
          }
          libs
        }
      </component>
    </module>
  }

  def nodePerExistingSourceFolder(paths: List[String]): NodeBuffer = nodePerExistingFolder(paths, false)
  def nodePerExistingTestSourceFolder(paths: List[String]): NodeBuffer = nodePerExistingFolder(paths, true)

  def nodePerExistingFolder(paths: List[String], isTestSourceFolders: Boolean): NodeBuffer = {
    val nodes = new scala.xml.NodeBuffer
    paths.filter(new File(projectPath, _).exists()).foreach(nodes &+ sourceFolder(_, isTestSourceFolders))
    nodes
  }

  def sourceFolder(path: String, isTestSourceFolder: Boolean) = {
      //println("Source: " + path)
      <sourceFolder url={"file://$MODULE_DIR$/" + path} isTestSource={isTestSourceFolder.toString} />
  }

  def webFacet(webProject: DefaultWebProject): Node = {
    <facet type="web" name="Web">
      <configuration>
        <descriptors>
          <deploymentDescriptor name="web.xml" url={String.format("file://$MODULE_DIR$/%s/WEB-INF/web.xml", relativePath(webProject.webappPath.asFile))} />
        </descriptors>
        <webroots>
          <root url={String.format("file://$MODULE_DIR$/%s", relativePath(webProject.webappPath.asFile))} relative="/" />
        </webroots>
      </configuration>
    </facet>
  }

  def moduleLibrary(scope: Option[String], sources: Option[String], javadoc: Option[String], classes: Option[String], relativePaths: Boolean): Node = {
    def root(entry: Option[String]) =
      entry.map { e =>
        val url = if (relativePaths) String.format("jar://$MODULE_DIR$/%s!/", e) else e
        <root url={url}/>
      }.getOrElse(NodeSeq.Empty)

    val orderEntry =
    <orderEntry type="module-library" exported=" ">
      <library>
        <CLASSES>
          { root(classes) }
        </CLASSES>
        <JAVADOC>
          { root(javadoc) }
        </JAVADOC>
        <SOURCES>
          { root(sources) }
        </SOURCES>
      </library>
    </orderEntry>

    scope match {
      case Some(s) => orderEntry % new UnprefixedAttribute("scope", s, scala.xml.Null)
      case _ => orderEntry
    }
  }
}
