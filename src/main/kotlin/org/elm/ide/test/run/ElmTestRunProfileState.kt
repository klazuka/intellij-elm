package org.elm.ide.test.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.autotest.ToggleAutoTestAction
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.events.*
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.notification.NotificationType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import org.elm.ide.notifications.showBalloon
import org.elm.ide.test.core.ElmProjectTestsHelper
import org.elm.ide.test.core.ElmTestJsonProcessor
import org.elm.workspace.elmWorkspace
import java.nio.file.Files

class ElmTestRunProfileState internal constructor(
        environment: ExecutionEnvironment,
        configuration: ElmTestRunConfiguration
) : CommandLineState(environment) {

    private val elmFolder =
            configuration.options.elmFolder?.takeIf { it.isNotEmpty() }
                    ?: environment.project.basePath

    private val elmProject =
            elmFolder?.let {
                ElmProjectTestsHelper(environment.project).elmProjectByProjectDirPath(elmFolder)
            }

    @Throws(ExecutionException::class)
    override fun startProcess(): ProcessHandler {
        FileDocumentManager.getInstance().saveAllDocuments()
        val project = environment.project
        val toolchain = project.elmWorkspace.settings.toolchain

        val elmTestCLI = toolchain.elmTestCLI
                ?: return handleBadConfiguration(project, "Missing path to elm-test")

        val elmCompilerBinary = toolchain.elmCompilerPath
                ?: return handleBadConfiguration(project, "Missing path to the Elm compiler")

        if (elmFolder == null) return handleBadConfiguration(project, "Missing path to elmFolder")
        if (elmProject == null) return handleBadConfiguration(project, "Could not find the Elm project for these tests")

        if (!Files.exists(elmCompilerBinary)) {
            return handleBadConfiguration(project, "Could not find the Elm compiler ")
        }

        return elmTestCLI.runTestsProcessHandler(elmCompilerBinary, elmProject)
    }

    @Throws(ExecutionException::class)
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val result = super.execute(executor, runner)
        if (result is DefaultExecutionResult) {
            result.setRestartActions(object : ToggleAutoTestAction() {
                override fun getAutoTestManager(project: Project) = project.elmAutoTestManager
            })
        }
        return result
    }

    @Throws(ExecutionException::class)
    private fun handleBadConfiguration(project: Project, errorMessage: String): ProcessHandler {
        project.showBalloon(
                errorMessage,
                NotificationType.ERROR,
                "Fix" to { project.elmWorkspace.showConfigureToolchainUI() }
        )
        throw ExecutionException(errorMessage)
    }

    override fun createConsole(executor: Executor): ConsoleView? {
        if (elmProject == null) error("Missing ElmProject")

        val runConfiguration = environment.runProfile as RunConfiguration
        val properties = ConsoleProperties(runConfiguration, executor, elmProject.testsRelativeDirPath)
        val consoleView = SMTRunnerConsoleView(properties)
        SMTestRunnerConnectionUtil.initConsoleView(consoleView, properties.testFrameworkName)
        return consoleView
    }

    private class ConsoleProperties internal constructor(
            config: RunConfiguration,
            executor: Executor,
            private val testsRelativeDirPath: String
    ) : SMTRunnerConsoleProperties(config, "elm-test", executor), SMCustomMessagesParsing {

        init {

            setIfUndefined(TestConsoleProperties.TRACK_RUNNING_TEST, true)
            setIfUndefined(TestConsoleProperties.OPEN_FAILURE_LINE, true)
            setIfUndefined(TestConsoleProperties.HIDE_PASSED_TESTS, false)
            setIfUndefined(TestConsoleProperties.SHOW_STATISTICS, true)
            setIfUndefined(TestConsoleProperties.SELECT_FIRST_DEFECT, true)
            setIfUndefined(TestConsoleProperties.SCROLL_TO_SOURCE, true)
            //            INCLUDE_NON_STARTED_IN_RERUN_FAILED
            //            setIdBasedTestTree(true);
            //            setPrintTestingStartedTime(false);
        }


        override fun createTestEventsConverter(testFrameworkName: String, consoleProperties: TestConsoleProperties): OutputToGeneralTestEventsConverter {
            return object : OutputToGeneralTestEventsConverter(testFrameworkName, consoleProperties) {
                var processor = ElmTestJsonProcessor(testsRelativeDirPath)

                @Synchronized
                override fun finishTesting() {
                    super.finishTesting()
                }

                override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
                    val events = processor.accept(text) ?: return false
                    events.forEach { processEvent(it) }
                    return true
                }

                private fun processEvent(event: TreeNodeEvent) {
                    when (event) {
                        is TestStartedEvent -> this.getProcessor().onTestStarted(event)
                        is TestFinishedEvent -> this.getProcessor().onTestFinished(event)
                        is TestFailedEvent -> this.getProcessor().onTestFailure(event)
                        is TestIgnoredEvent -> this.getProcessor().onTestIgnored(event)
                        is TestSuiteStartedEvent -> this.getProcessor().onSuiteStarted(event)
                        is TestSuiteFinishedEvent -> this.getProcessor().onSuiteFinished(event)
                    }
                }
            }
        }

        override fun getTestLocator() = ElmTestLocator
    }
}
