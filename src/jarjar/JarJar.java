package jarjar;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static ox.util.Utils.formatBytes;
import static ox.util.Utils.normalize;
import static ox.util.Utils.only;
import static ox.util.Utils.propagate;

import java.util.zip.ZipEntry;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;

import ox.File;
import ox.IO;
import ox.Log;
import ox.util.Utils;
import ox.x.XList;
import ox.x.XMap;
import ox.x.XMultimap;
import ox.x.XSet;

public class JarJar {

  private final XList<File> projectDirs = XList.create();
  private String mainClass = "";
  private XList<File> outputFiles;
  private XMap<File, XMultimap<File, BuildConfig>> projectCache = XMap.create();
  private boolean verbose = false, clean = true, compile = true, multiRelease = true; 
  private XSet<String> blacklist = XSet.create();

  private JarJar(File projectDir) {
    this.projectDirs.add(checkNotNull(projectDir));
  }

  public JarJar main(String mainClass) {
    this.mainClass = normalize(mainClass);
    return this;
  }

  public JarJar multiRelease(boolean multiRelease) {
    this.multiRelease = multiRelease;
    return this;
  }

  public JarJar skipCompile() {
    compile = false;
    clean = false;
    return this;
  }

  public JarJar clean(boolean clean) {
    this.clean = clean;
    return this;
  }

  public JarJar verbose() {
    verbose = true;
    return this;
  }

  public void build(File outputFile) {
    build(XList.of(outputFile));
  }

  public void build(XList<File> outputFiles) {
    this.outputFiles = outputFiles;

    Stopwatch watch = Stopwatch.createStarted();
    XMultimap<File, BuildConfig> classpath = new XMultimap<>(HashMultimap.create());
    projectDirs.forEach(projectDir -> {
      classpath.putAll(compileProject(projectDir, 0));
    });
    Log.debug("Compiled " + watch);

    watch.reset().start();
    exportJar(classpath);

    for (int i = 0; i < outputFiles.size(); i++) {
      File outputFile = outputFiles.get(i);
      if (i > 0) {
        outputFiles.get(0).copyTo(outputFile);
      }
      Log.debug("JAR created: %s %s (%s)", outputFile, watch, Utils.formatBytes(outputFile.length()));
    }
  }

  private void exportJar(XMultimap<File, BuildConfig> classpath) {
    Zipper zipper = new Zipper(outputFiles.get(0));
    XMultimap<String, File> exportedFiles = XMultimap.create();
    try {
      classpath.asMap().forEach((classpathEntry, buildConfigs) -> {
        try {
          if (classpathEntry.isDirectory()) {
            if (verbose) {
              Log.debug("Copying tree: %s (%s)", classpathEntry, formatBytes(classpathEntry.totalSize()));
            }
            classpathEntry.walkTree(file -> {
              if (!file.isDirectory()) {
                String name = file.getRelativePath(classpathEntry);
                if (!only(buildConfigs).shouldInclude(name)) {
                  if (verbose) {
                    Log.warn("Skipping " + name);
                  }
                  return;
                }
                if (verbose || file.length() > 1_000_000) {
                  Log.debug(name + " " + formatBytes(file.length()));
                }
                exportedFiles.put(name, file);
                XList<File> files = exportedFiles.get(name);
                if (files.size() > 1) {
                  if (verbose) {
                    Log.warn("Duplicate file: " + name + " in: " + files);
                  }
                  return;
                }
                zipper.putNextEntry(name, IO.from(file).gzipInput(false).zipInput(false).asStream());
              }
            });
          } else {
            if (verbose) {
              Log.debug(classpathEntry + " :: " + buildConfigs);
            }
            copyJarToJar(classpathEntry, zipper, exportedFiles, only(buildConfigs));
          }
        } catch (Exception e) {
          throw propagate(e);
        }
      });
      addManifest(zipper);
    } finally {
      zipper.close();
    }
  }

  private void addManifest(Zipper zipper) {
    StringBuilder sb = new StringBuilder("Manifest-Version: 1.0\n");
    if (!mainClass.isEmpty()) {
      sb.append("Main-Class: " + mainClass + "\n");
    }
    zipper.putNextEntry("META-INF/MANIFEST.MF", IO.from(sb.toString()).asStream());
  }

  private void copyJarToJar(File sourceFile, Zipper zipper, XMultimap<String, File> exportedFiles, BuildConfig config) {
    if (!config.shouldInclude(sourceFile.getName())) {
      if (verbose) {
        Log.warn("Skipping jar: " + sourceFile);
      }
      return;
    }
    if (verbose) {
      Log.debug("Copying jar: %s (%s)", sourceFile, Utils.formatBytes(sourceFile.length()));
    }

    Unzipper unzipper = new Unzipper(sourceFile);
    try {
      unzipper.forEach(entry -> {
        if (!shouldCopyFile(sourceFile, entry, exportedFiles, config)) {
          return;
        }
        String fileName = entry.getName();
        if (verbose || entry.getSize() > 1_000_000) {
          Log.debug("%s (%s)", fileName, formatBytes(entry.getSize()));
        }
        zipper.putNextEntry(fileName, unzipper.openStream(entry));
      });
    } finally {
      unzipper.finish();
    }
  }

  private boolean shouldCopyFile(File jarFile, ZipEntry entry, XMultimap<String, File> exportedFiles,
      BuildConfig config) {
    if (entry.isDirectory()) {
      return false;
    }
    String fileName = entry.getName();
    if (fileName.startsWith("META-INF/") && !fileName.startsWith("META-INF/services")) {
      if (verbose) {
        Log.debug("Skipping file: " + fileName);
      }
      return false;
    }

    if (!config.shouldInclude(jarFile, fileName)) {
      Log.debug("Skipping non-whitelisted file: " + fileName);
      return false;
    }
    exportedFiles.put(fileName, jarFile);
    XList<File> jars = exportedFiles.get(fileName);
    if (jars.size() > 1) {
      if (!fileName.equals("module-info.class")) {
        if (verbose) {
          Log.warn("Duplicate file: " + fileName + " in jars: " + jars);
        }
      }
      return false;
    }

    return true;
  }

  /**
   * Returns the classpath for this project.
   */
  private XMultimap<File, BuildConfig> compileProject(File projectDir, int depth) {
    checkState(projectDir.exists(), "Could not find dir: " + projectDir);
    checkState(projectDir.isDirectory());

    if (projectCache.containsKey(projectDir)) {
      return projectCache.get(projectDir);
    }

    BuildConfig buildFile = BuildConfig.getForProject(projectDir);

    Document doc = Jsoup.parse(IO.from(projectDir.child(".classpath")).toString());

    XMultimap<String, Element> classpathEntries = XMultimap.create();
    doc.select("classpathentry").forEach(entry -> {
      classpathEntries.put(entry.attr("kind"), entry);
    });

    XSet<File> myClasspath = XSet.create();
    XMultimap<File, BuildConfig> exportedClasspath = new XMultimap<>(LinkedHashMultimap.create());

    XList<File> srcPaths = XList.create();

    for (Element e : classpathEntries.get("src")) {
      if (blacklist.contains(e.attr("path"))) {
        Log.debug("Blacklisted: " + e);
        continue;
      }
      if (e.hasAttr("combineaccessrules")) {
        // recursively compile this other project
        File dir = findProject(e.attr("path"));
        XMultimap<File, BuildConfig> projectExports = compileProject(dir, depth + 1);
        exportedClasspath.putAll(projectExports);
        myClasspath.addAll(projectExports.keySet());
      } else {
        File srcPath = projectDir.child(e.attr("path"));
        myClasspath.add(srcPath);
        srcPaths.add(srcPath);
      }
    }

    classpathEntries.get("lib").forEach(e -> {
      File jar = projectDir.child(e.attr("path"));
      myClasspath.add(jar);
      if ("true".equals(e.attr("exported")) || depth == 0 || true) {
        exportedClasspath.put(jar, buildFile);
      }
    });

    if (srcPaths.hasData()) {
      File binDir = srcPaths.get(0).sibling("bin");
      if (clean) {
        binDir.empty();
      }
      myClasspath.add(binDir);
      exportedClasspath.put(binDir, buildFile);

      srcPaths.forEach(srcPath -> {
        srcPath.walkTree(file -> {
          if (!file.isDirectory() && !file.extension().equals("java")) {
            File target = binDir.child(file.getRelativePath(srcPath));
            file.copyTo(target.mkdirs());
          }
        });
      });

      if (compile) {
        JavaCompiler.target(binDir)
            .classpath(myClasspath)
            .compile(srcPaths);
      }
    }

    projectCache.put(projectDir, exportedClasspath);

    return exportedClasspath;
  }

  private File findProject(String path) {
    for (File projectDir : projectDirs) {
      File ret = projectDir.parent().child(path);
      if (ret.exists()) {
        return ret;
      }
      ret = projectDir.parent().parent().child(path);
      if (ret.exists()) {
        return ret;
      }
    }
    throw new RuntimeException("Could not find project: " + path);
  }

  public JarJar addProject(File projectDir) {
    this.projectDirs.add(projectDir);
    return this;
  }

  public JarJar blacklist(String s) {
    this.blacklist.add(s);
    return this;
  }

  public static JarJar project(File projectDir) {
    return new JarJar(projectDir);
  }

  private static void buildJarJar() {
    JarJar.project(File.home("workspace/JarJar"))
        .main("jarjar.JarJarCommandLine")
        .clean(false)
        .blacklist("test")
        .build(File.home("workspace/JarJar/dist/JarJar.jar"));
  }

  public static void main(String[] args) {
    buildJarJar();

    // JarJar.project(File.home("workspace/bowser"))
    // .skipCompile()
    // .blacklist("/ox")
    // .build(File.downloads("bowser.jar"));

    // JarJar.project(File.home("workspace/EZDB"))
    // .skipCompile().verbose()
    // .build(File.downloads("ezdb.jar"));

    // JarJar.project(File.home("workspace/ender/ender.com"))
    // // .verbose()
    // .skipCompile()
    // .main("ender.EnderServer")
    // .build(File.downloads("EnderServer.jar"));

    // JarJar.project(File.home("workspace/ender/gremlin.ender.com"))
    // .main("gremlin.GremlinServer")
    // .skipCompile()
    // .clean(false)
    // // .verbose()
    // .build(File.downloads("GremlinServer.jar"));

    Log.debug("Done");
  }

}
