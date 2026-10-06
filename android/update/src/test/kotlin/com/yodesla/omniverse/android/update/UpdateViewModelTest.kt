package com.yodesla.omniverse.android.update

import com.yodesla.omniverse.core.net.HttpClient
import com.yodesla.omniverse.core.net.HttpResponse
import com.yodesla.omniverse.core.source.SourceException
import com.yodesla.omniverse.core.update.UpdateCheck
import com.yodesla.omniverse.core.update.UpdateChecker
import com.yodesla.omniverse.core.update.UpdateManifest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

private val noHttp = object : HttpClient {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        error("no network in this test")
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun manifest(mandatory: Boolean = false) = UpdateManifest(
        versionCode = 7,
        versionName = "0.3.0",
        apkUrl = "https://cdn.example.com/omniverse-0.3.0.apk",
        sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        mandatory = mandatory,
    )

    private fun fakeChecker(result: UpdateCheck) = object : UpdateChecker(noHttp, "https://cdn.example.com/manifest.json", 6, 34) {
        override suspend fun check(): UpdateCheck = result
    }

    private class FakeInstaller(
        var canInstallResult: Boolean = true,
        var download: (suspend (UpdateManifest, (Float) -> Unit) -> File)? = null,
    ) : ApkInstaller(android.app.Application(), noHttp) {
        val installs = mutableListOf<File>()
        var downloadCount = 0

        override suspend fun download(manifest: UpdateManifest, onProgress: (Float) -> Unit): File {
            downloadCount++
            onProgress(1f)
            return download?.invoke(manifest, onProgress) ?: File.createTempFile("update", ".apk")
        }

        override fun canInstall(): Boolean = canInstallResult

        override fun install(file: File) {
            installs += file
        }
    }

    private fun vm(checker: UpdateChecker?, installer: FakeInstaller) = UpdateViewModel(checker, installer)

    @Test
    fun checkerNullAlwaysHidden() = runTest(dispatcher) {
        val vm = vm(checker = null, installer = FakeInstaller())
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateUiState.Hidden, vm.state.value)
        vm.accept()
        advanceUntilIdle()
        assertEquals(UpdateUiState.Hidden, vm.state.value)
    }

    @Test
    fun upToDateStaysHidden() = runTest(dispatcher) {
        val vm = vm(fakeChecker(UpdateCheck.UpToDate), FakeInstaller())
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateUiState.Hidden, vm.state.value)
    }

    @Test
    fun failedCheckIsSilentAndStaysHidden() = runTest(dispatcher) {
        val vm = vm(fakeChecker(UpdateCheck.Failed("dns down")), FakeInstaller())
        vm.checkNow()
        advanceUntilIdle()
        assertEquals(UpdateUiState.Hidden, vm.state.value)
    }

    @Test
    fun acceptDownloadsAndInstallsWhenPermissionGranted() = runTest(dispatcher) {
        val installer = FakeInstaller(canInstallResult = true)
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), installer)
        vm.checkNow()
        advanceUntilIdle()
        assertIs<UpdateUiState.Available>(vm.state.value)

        vm.accept()
        advanceUntilIdle()

        assertEquals(1, installer.downloadCount)
        val state = assertIs<UpdateUiState.ReadyToInstall>(vm.state.value)
        assertEquals(1, installer.installs.size)
        assertEquals(state.file, installer.installs.single())
    }

    @Test
    fun acceptWaitsForPermissionWhenNotGranted() = runTest(dispatcher) {
        val installer = FakeInstaller(canInstallResult = false)
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), installer)
        vm.checkNow()
        advanceUntilIdle()
        vm.accept()
        advanceUntilIdle()

        assertIs<UpdateUiState.NeedsPermission>(vm.state.value)
        assertEquals(emptyList(), installer.installs)

        installer.canInstallResult = true
        vm.retryInstall()
        assertIs<UpdateUiState.ReadyToInstall>(vm.state.value)
        assertEquals(1, installer.installs.size)
    }

    @Test
    fun backingOutOfSettingsKeepsTheExplanation() = runTest(dispatcher) {
        val installer = FakeInstaller(canInstallResult = false)
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), installer)
        vm.checkNow()
        advanceUntilIdle()
        vm.accept()
        advanceUntilIdle()
        assertIs<UpdateUiState.NeedsPermission>(vm.state.value)

        // retryInstall also runs on every resume; no permission yet → stay put, don't error.
        vm.retryInstall()
        assertIs<UpdateUiState.NeedsPermission>(vm.state.value)
        assertEquals(emptyList(), installer.installs)
    }

    @Test
    fun installButtonReFiresTheInstaller() = runTest(dispatcher) {
        val installer = FakeInstaller(canInstallResult = true)
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), installer)
        vm.checkNow()
        advanceUntilIdle()
        vm.accept()
        advanceUntilIdle()
        assertIs<UpdateUiState.ReadyToInstall>(vm.state.value)

        vm.retryInstall() // the user dismissed the system dialog and pressed "Install" again
        assertEquals(2, installer.installs.size)
    }

    @Test
    fun laterHidesWhenNotMandatory() = runTest(dispatcher) {
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), FakeInstaller())
        vm.checkNow()
        advanceUntilIdle()
        assertIs<UpdateUiState.Available>(vm.state.value)

        vm.later()
        assertEquals(UpdateUiState.Hidden, vm.state.value)
    }

    @Test
    fun laterKeepsPromptWhenMandatory() = runTest(dispatcher) {
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest(mandatory = true))), FakeInstaller())
        vm.checkNow()
        advanceUntilIdle()
        vm.later()
        assertIs<UpdateUiState.Available>(vm.state.value)
    }

    @Test
    fun downloadNetworkFailureShowsGenericError() = runTest(dispatcher) {
        val installer = FakeInstaller(download = { _, _ -> throw SourceException.Network("timeout") })
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), installer)
        vm.checkNow()
        advanceUntilIdle()
        vm.accept()
        advanceUntilIdle()
        val state = assertIs<UpdateUiState.Error>(vm.state.value)
        assertEquals(R.string.update_error_generic, state.messageRes)
    }

    @Test
    fun downloadChecksumMismatchShowsDamagedError() = runTest(dispatcher) {
        val installer = FakeInstaller(download = { _, _ -> throw SourceException.BadResponse("Update file is damaged") })
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), installer)
        vm.checkNow()
        advanceUntilIdle()
        vm.accept()
        advanceUntilIdle()
        val state = assertIs<UpdateUiState.Error>(vm.state.value)
        assertEquals(R.string.update_error_damaged, state.messageRes)
    }

    @Test
    fun downloadProgressIsReportedThenReadyToInstall() = runTest(dispatcher) {
        val installer = FakeInstaller(
            download = { _, onProgress ->
                onProgress(0.25f)
                onProgress(0.5f)
                File.createTempFile("update", ".apk")
            },
        )
        val vm = vm(fakeChecker(UpdateCheck.Available(manifest())), installer)
        vm.checkNow()
        advanceUntilIdle()
        vm.accept()
        advanceUntilIdle()
        assertIs<UpdateUiState.ReadyToInstall>(vm.state.value)
    }
}
