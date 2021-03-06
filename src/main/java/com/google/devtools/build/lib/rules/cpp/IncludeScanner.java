// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.EnvironmentalExecException;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans source files to determine the bounding set of transitively referenced include files.
 *
 * <p>Note that include scanning is performance-critical code.
 */
public interface IncludeScanner {
  /**
   * Processes source files and a list of includes extracted from command line
   * flags. Adds all found files to the provided set {@code includes}. This
   * method takes into account the path- and file-level hints that are part of
   * this include scanner.
   * 
   * <p>{@code mainSource} is the source file relative to which the {@code cmdlineIncludes} are
   * interpreted.
   */
  public void process(Artifact mainSource, Collection<Artifact> sources,
      Map<Artifact, Path> legalOutputPaths, List<String> cmdlineIncludes, Set<Artifact> includes,
      ActionExecutionContext actionExecutionContext) throws IOException, ExecException,
      InterruptedException;

  /** Supplies IncludeScanners upon request. */
  interface IncludeScannerSupplier {
    /** Returns the possibly shared scanner to be used for a given pair of include paths. */
    IncludeScanner scannerFor(List<Path> quoteIncludePaths, List<Path> includePaths);
  }

  /**
   * Helper class that exists just to provide a static method that prepares the arguments with which
   * to call an IncludeScanner.
   */
  class IncludeScanningPreparer {
    private IncludeScanningPreparer() {}

    /**
     * Returns the files transitively included by the source files of the given IncludeScannable.
     *
     * @param action IncludeScannable whose sources' transitive includes will be returned.
     * @param includeScannerSupplier supplies IncludeScanners to actually do the transitive
     *                               scanning (and caching results) for a given source file.
     * @param actionExecutionContext the context for {@code action}.
     * @param profilerTaskName what the {@link Profiler} should record this call for.
     */
    public static Collection<Artifact> scanForIncludedInputs(IncludeScannable action,
        IncludeScannerSupplier includeScannerSupplier,
        ActionExecutionContext actionExecutionContext,
        String profilerTaskName)
        throws ExecException, InterruptedException, ActionExecutionException {

      Set<Artifact> includes = Sets.newConcurrentHashSet();

      Executor executor = actionExecutionContext.getExecutor();
      Path execRoot = executor.getExecRoot();

      final List<Path> absoluteBuiltInIncludeDirs = new ArrayList<>();

      Profiler profiler = Profiler.instance();
      try {
        profiler.startTask(ProfilerTask.SCANNER, profilerTaskName);

        // We need to scan the action itself, but also the auxiliary scannables
        // (for LIPO). There is no need to call getAuxiliaryScannables
        // recursively.
        for (IncludeScannable scannable :
          Iterables.concat(ImmutableList.of(action), action.getAuxiliaryScannables())) {

          Map<Artifact, Path> legalOutputPaths = scannable.getLegalGeneratedScannerFileMap();
          List<PathFragment> includeDirs = new ArrayList<>(scannable.getIncludeDirs());
          List<PathFragment> quoteIncludeDirs = scannable.getQuoteIncludeDirs();
          List<String> cmdlineIncludes = scannable.getCmdlineIncludes();

          includeDirs.addAll(scannable.getSystemIncludeDirs());

          // Add the system include paths to the list of include paths.
          for (PathFragment pathFragment : action.getBuiltInIncludeDirectories()) {
            if (pathFragment.isAbsolute()) {
              absoluteBuiltInIncludeDirs.add(execRoot.getRelative(pathFragment));
            }
            includeDirs.add(pathFragment);
          }

          IncludeScanner scanner = includeScannerSupplier.scannerFor(
              relativeTo(execRoot, quoteIncludeDirs),
              relativeTo(execRoot, includeDirs));

          Artifact mainSource =  scannable.getMainIncludeScannerSource();
          Collection<Artifact> sources = scannable.getIncludeScannerSources();
          // Add all include scanning entry points to the inputs; this is necessary
          // when we have more than one source to scan from, for example when building
          // C++ modules.
          // In that case we have one of two cases:
          // 1. We compile a header module - there, the .cppmap file is the main source file
          //    (which we do not include-scan, as that would require an extra parser), and
          //    thus already in the input; all headers in the .cppmap file are our entry points
          //    for include scanning, but are not yet in the inputs - they get added here.
          // 2. We compile an object file that uses a header module; currently using a header
          //    module requires all headers it can reference to be available for the compilation.
          //    The header module can reference headers that are not in the transitive include
          //    closure of the current translation unit. Therefore, {@code CppCompileAction}
          //    adds all headers specified transitively for compiled header modules as include
          //    scanning entry points, and we need to add the entry points to the inputs here.
          includes.addAll(sources);
          scanner.process(mainSource, sources, legalOutputPaths, cmdlineIncludes, includes,
                actionExecutionContext);
        }
      } catch (IOException e) {
        throw new EnvironmentalExecException(e.getMessage());
      } finally {
        profiler.completeTask(ProfilerTask.SCANNER);
      }

      // Collect inputs and output
      List<Artifact> inputs = new ArrayList<>();
      for (Artifact included : includes) {
        if (FileSystemUtils.startsWithAny(included.getPath(), absoluteBuiltInIncludeDirs)) {
          // Skip include files found in absolute include directories. This currently only applies
          // to grte.
          continue;
        }
        if (included.getRoot().getPath().getParentDirectory() == null) {
          throw new UserExecException(
              "illegal absolute path to include file: " + included.getPath());
        }
        inputs.add(included);
      }
      return inputs;
    }

    private static List<Path> relativeTo(
        Path path, Collection<PathFragment> fragments) {
      List<Path> result = Lists.newArrayListWithCapacity(fragments.size());
      for (PathFragment fragment : fragments) {
        result.add(path.getRelative(fragment));
      }
      return result;
    }
  }
}
