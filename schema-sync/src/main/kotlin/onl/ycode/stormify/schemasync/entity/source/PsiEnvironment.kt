package onl.ycode.stormify.schemasync.entity.source

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import java.io.Closeable
import java.nio.file.Path

/**
 * Sets up a minimal Kotlin compiler environment used purely for PSI parsing
 * (no resolution, no binding context). Disposed via [close].
 */
class PsiEnvironment : Closeable {
    private val disposable: Disposable = Disposer.newDisposable("schema-sync-psi")
    private val environment: KotlinCoreEnvironment
    private val localFs = CoreLocalFileSystem()

    init {
        val cfg = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "schema-sync")
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            cfg,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    fun parse(path: Path): KtFile? {
        val vf = localFs.findFileByIoFile(path.toFile()) ?: return null
        val psi = PsiManager.getInstance(environment.project).findFile(vf) ?: return null
        return psi as? KtFile
    }

    override fun close() {
        Disposer.dispose(disposable)
    }
}
