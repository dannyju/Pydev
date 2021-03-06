/**
 * Copyright (c) 2005-2011 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Eclipse Public License (EPL).
 * Please see the license.txt included with this distribution for details.
 * Any modifications to this file must keep this entire header intact.
 */
/*
 * Created on Oct 25, 2004
 * 
 * @author Fabio Zadrozny
 */
package org.python.pydev.builder.pylint;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.ui.console.IOConsoleOutputStream;
import org.python.pydev.builder.PyDevBuilderVisitor;
import org.python.pydev.builder.PydevMarkerUtils;
import org.python.pydev.builder.PydevMarkerUtils.MarkerInfo;
import org.python.pydev.consoles.MessageConsoles;
import org.python.pydev.core.IInterpreterManager;
import org.python.pydev.core.MisconfigurationException;
import org.python.pydev.core.PythonNatureWithoutProjectException;
import org.python.pydev.core.callbacks.ICallback0;
import org.python.pydev.core.docutils.StringUtils;
import org.python.pydev.core.log.Log;
import org.python.pydev.plugin.PydevPlugin;
import org.python.pydev.plugin.nature.PythonNature;
import org.python.pydev.runners.SimplePythonRunner;
import org.python.pydev.shared_core.io.FileUtils;
import org.python.pydev.shared_core.structure.Tuple;
import org.python.pydev.ui.UIConstants;

/**
 * 
 * Check lint.py for options.
 * 
 * @author Fabio Zadrozny
 */
public class PyLintVisitor extends PyDevBuilderVisitor {

    /* (non-Javadoc)
     * @see org.python.pydev.builder.PyDevBuilderVisitor#visitResource(org.eclipse.core.resources.IResource)
     */
    public static final String PYLINT_PROBLEM_MARKER = "org.python.pydev.pylintproblemmarker";

    public static final List<PyLintThread> pyLintThreads = new ArrayList<PyLintThread>();

    private static Object lock = new Object();

    /**
     * This class runs as a thread to get the markers, and only stops the IDE when the markers are being added.
     * 
     * @author Fabio Zadrozny
     */
    public static class PyLintThread extends Thread {

        IResource resource;
        ICallback0<IDocument> document;
        IPath location;

        List<Object[]> markers = new ArrayList<Object[]>();

        public PyLintThread(IResource resource, ICallback0<IDocument> document, IPath location) {
            setName("PyLint thread");
            this.resource = resource;
            this.document = document;
            this.location = location;
        }

        /**
         * @return
         */
        private boolean canPassPyLint() {
            if (pyLintThreads.size() < PyLintPrefPage.getMaxPyLintDelta()) {
                pyLintThreads.add(this);
                return true;
            }
            return false;
        }

        /**
         * @see java.lang.Thread#run()
         */
        public void run() {
            try {
                if (canPassPyLint()) {

                    IOConsoleOutputStream out = getConsoleOutputStream();

                    final IDocument doc = document.call();
                    passPyLint(resource, out, doc);

                    new Job("Adding markers") {

                        protected IStatus run(IProgressMonitor monitor) {

                            ArrayList<MarkerInfo> lst = new ArrayList<PydevMarkerUtils.MarkerInfo>();

                            for (Iterator<Object[]> iter = markers.iterator(); iter.hasNext();) {
                                Object[] el = iter.next();

                                String tok = (String) el[0];
                                int priority = ((Integer) el[1]).intValue();
                                String id = (String) el[2];
                                int line = ((Integer) el[3]).intValue();

                                lst.add(new PydevMarkerUtils.MarkerInfo(doc, "ID:" + id + " " + tok,
                                        PYLINT_PROBLEM_MARKER, priority, false, false, line, 0, line, 0, null));
                            }

                            PydevMarkerUtils.replaceMarkers(lst, resource, PYLINT_PROBLEM_MARKER, true, monitor);

                            return PydevPlugin.makeStatus(Status.OK, "", null);
                        }
                    }.schedule();
                }

            } catch (final Exception e) {
                new Job("Error reporting") {
                    protected IStatus run(IProgressMonitor monitor) {
                        Log.log(e);
                        return PydevPlugin.makeStatus(Status.OK, "", null);
                    }
                }.schedule();
            } finally {
                try {
                    pyLintThreads.remove(this);
                } catch (Exception e) {
                    Log.log(e);
                }
            }
        }

        private IOConsoleOutputStream getConsoleOutputStream() throws MalformedURLException {
            if (PyLintPrefPage.useConsole()) {
                return MessageConsoles.getConsoleOutputStream("PyLint", UIConstants.PY_LINT_ICON);
            } else {
                return null;
            }
        }

        /**
         * @param tok
         * @param type
         * @param priority
         * @param id
         * @param line
         */
        private void addToMarkers(String tok, int priority, String id, int line) {
            markers.add(new Object[] { tok, priority, id, line });
        }

        /**
         * @param resource
         * @param out 
         * @param doc 
         * @param document
         * @param location
         * @throws CoreException
         * @throws MisconfigurationException 
         * @throws PythonNatureWithoutProjectException 
         */
        private void passPyLint(IResource resource, IOConsoleOutputStream out, IDocument doc) throws CoreException,
                MisconfigurationException, PythonNatureWithoutProjectException {
            File script = new File(PyLintPrefPage.getPyLintLocation());
            File arg = new File(location.toOSString());

            ArrayList<String> list = new ArrayList<String>();
            list.add("--include-ids=y");

            //user args
            String userArgs = StringUtils.replaceNewLines(PyLintPrefPage.getPyLintArgs(), " ");
            StringTokenizer tokenizer2 = new StringTokenizer(userArgs);
            while (tokenizer2.hasMoreTokens()) {
                list.add(tokenizer2.nextToken());
            }
            list.add(FileUtils.getFileAbsolutePath(arg));

            IProject project = resource.getProject();

            String scriptToExe = FileUtils.getFileAbsolutePath(script);
            String[] paramsToExe = list.toArray(new String[0]);
            write("PyLint: Executing command line:'", out, scriptToExe, paramsToExe, "'");

            PythonNature nature = PythonNature.getPythonNature(project);
            if (nature == null) {
                Throwable e = new RuntimeException("PyLint ERROR: Nature not configured for: " + project);
                Log.log(e);
                return;
            }

            Tuple<String, String> outTup = new SimplePythonRunner().runAndGetOutputFromPythonScript(nature
                    .getProjectInterpreter().getExecutableOrJar(), scriptToExe, paramsToExe, arg.getParentFile(),
                    project);

            write("PyLint: The stdout of the command line is: " + outTup.o1, out);
            write("PyLint: The stderr of the command line is: " + outTup.o2, out);

            String output = outTup.o1;

            StringTokenizer tokenizer = new StringTokenizer(output, "\r\n");

            boolean useW = PyLintPrefPage.useWarnings();
            boolean useE = PyLintPrefPage.useErrors();
            boolean useF = PyLintPrefPage.useFatal();
            boolean useC = PyLintPrefPage.useCodingStandard();
            boolean useR = PyLintPrefPage.useRefactorTips();

            //Set up local values for severity
            int wSeverity = PyLintPrefPage.wSeverity();
            int eSeverity = PyLintPrefPage.eSeverity();
            int fSeverity = PyLintPrefPage.fSeverity();
            int cSeverity = PyLintPrefPage.cSeverity();
            int rSeverity = PyLintPrefPage.rSeverity();

            //System.out.println(output);
            if (output.indexOf("Traceback (most recent call last):") != -1) {
                Throwable e = new RuntimeException("PyLint ERROR: \n" + output);
                Log.log(e);
                return;
            }
            if (outTup.o2.indexOf("Traceback (most recent call last):") != -1) {
                Throwable e = new RuntimeException("PyLint ERROR: \n" + outTup.o2);
                Log.log(e);
                return;
            }
            while (tokenizer.hasMoreTokens()) {
                String tok = tokenizer.nextToken();

                try {
                    boolean found = false;
                    int priority = 0;

                    //W0611:  3: Unused import finalize
                    //F0001:  0: Unable to load module test.test2 (list index out of range)
                    //C0321: 25:fdfd: More than one statement on a single line
                    int indexOfDoublePoints = tok.indexOf(":");
                    if (indexOfDoublePoints != -1) {

                        if (tok.startsWith("C") && useC) {
                            found = true;
                            //priority = IMarker.SEVERITY_WARNING;
                            priority = cSeverity;
                        } else if (tok.startsWith("R") && useR) {
                            found = true;
                            //priority = IMarker.SEVERITY_WARNING;
                            priority = rSeverity;
                        } else if (tok.startsWith("W") && useW) {
                            found = true;
                            //priority = IMarker.SEVERITY_WARNING;
                            priority = wSeverity;
                        } else if (tok.startsWith("E") && useE) {
                            found = true;
                            //priority = IMarker.SEVERITY_ERROR;
                            priority = eSeverity;
                        } else if (tok.startsWith("F") && useF) {
                            found = true;
                            //priority = IMarker.SEVERITY_ERROR;
                            priority = fSeverity;
                        } else {
                            continue;
                        }

                    } else {
                        continue;
                    }

                    try {
                        if (found) {
                            String id = tok.substring(0, tok.indexOf(":")).trim();

                            int i = tok.indexOf(":");
                            if (i == -1)
                                continue;

                            tok = tok.substring(i + 1);

                            i = tok.indexOf(":");
                            if (i == -1)
                                continue;

                            final String substring = tok.substring(0, i).trim();
                            //On PyLint 0.24 it started giving line,col (and not only the line).
                            int line = Integer.parseInt(StringUtils.split(substring, ',').get(0));

                            IRegion region = null;
                            try {
                                region = doc.getLineInformation(line - 1);
                            } catch (Exception e) {
                                region = doc.getLineInformation(line);
                            }
                            String lineContents = doc.get(region.getOffset(), region.getLength());

                            int pos = -1;
                            if ((pos = lineContents.indexOf("IGNORE:")) != -1) {
                                String lintW = lineContents.substring(pos + "IGNORE:".length());
                                if (lintW.startsWith(id)) {
                                    continue;
                                }
                            }

                            i = tok.indexOf(":");
                            if (i == -1)
                                continue;

                            tok = tok.substring(i + 1);
                            addToMarkers(tok, priority, id, line - 1);
                        }
                    } catch (RuntimeException e2) {
                        Log.log(e2);
                    }
                } catch (Exception e1) {
                    Log.log(e1);
                }
            }
        }

    }

    @Override
    public void visitChangedResource(IResource resource, ICallback0<IDocument> document, IProgressMonitor monitor) {
        if (document == null) {
            return;
        }
        //Whenever PyLint is passed, the markers will be deleted.
        try {
            resource.deleteMarkers(PYLINT_PROBLEM_MARKER, false, IResource.DEPTH_ZERO);
        } catch (CoreException e3) {
            Log.log(e3);
        }
        if (PyLintPrefPage.usePyLint() == false) {
            return;
        }

        IProject project = resource.getProject();
        PythonNature pythonNature = PythonNature.getPythonNature(project);
        try {
            //pylint can only be used for jython projects
            if (pythonNature.getInterpreterType() != IInterpreterManager.INTERPRETER_TYPE_PYTHON) {
                return;
            }
            //must be in a source folder (not external)
            if (!isResourceInPythonpathProjectSources(resource, pythonNature, false)) {
                return;
            }
        } catch (Exception e) {
            return;
        }
        if (project != null && resource instanceof IFile) {

            IFile file = (IFile) resource;
            IPath location = file.getRawLocation();
            if (location != null) {
                PyLintThread thread = new PyLintThread(resource, document, location);
                thread.start();
            }
        }
    }

    public static void write(String cmdLineToExe, IOConsoleOutputStream out, Object... args) {
        try {
            if (out != null) {
                synchronized (lock) {
                    if (args != null) {
                        for (Object arg : args) {
                            if (arg instanceof String) {
                                cmdLineToExe += " " + arg;
                            } else if (arg instanceof String[]) {
                                String[] strings = (String[]) arg;
                                for (String string : strings) {
                                    cmdLineToExe += " " + string;
                                }
                            }
                        }
                    }
                    out.write(cmdLineToExe);
                }
            }
        } catch (IOException e) {
            Log.log(e);
        }
    }

    @Override
    public void visitRemovedResource(IResource resource, ICallback0<IDocument> document, IProgressMonitor monitor) {
    }

    /**
     * @see org.python.pydev.builder.PyDevBuilderVisitor#maxResourcesToVisit()
     */
    public int maxResourcesToVisit() {
        int i = PyLintPrefPage.getMaxPyLintDelta();
        if (i < 0) {
            i = 0;
        }
        return i;
    }
}
