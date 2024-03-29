package eu.darken.sdmse.appcontrol.core.automation.specs.miui

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import dagger.Binds
import dagger.Module
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import eu.darken.sdmse.common.device.RomType
import eu.darken.sdmse.appcleaner.core.automation.specs.miui.MIUISpecs
import eu.darken.sdmse.appcontrol.core.AppControlSettings
import eu.darken.sdmse.appcontrol.core.automation.specs.AppControlSpecGenerator
import eu.darken.sdmse.appcontrol.core.automation.specs.aosp.AOSPLabels
import eu.darken.sdmse.automation.core.common.StepAbortException
import eu.darken.sdmse.automation.core.common.StepProcessor
import eu.darken.sdmse.automation.core.common.clickableParent
import eu.darken.sdmse.automation.core.common.crawl
import eu.darken.sdmse.automation.core.common.defaultClick
import eu.darken.sdmse.automation.core.common.defaultWindowIntent
import eu.darken.sdmse.automation.core.common.getAospClearCacheClick
import eu.darken.sdmse.automation.core.common.getDefaultNodeRecovery
import eu.darken.sdmse.automation.core.common.getSysLocale
import eu.darken.sdmse.automation.core.common.idContains
import eu.darken.sdmse.automation.core.common.idMatches
import eu.darken.sdmse.automation.core.common.isClickyButton
import eu.darken.sdmse.automation.core.common.isTextView
import eu.darken.sdmse.automation.core.common.pkgId
import eu.darken.sdmse.automation.core.common.textEndsWithAny
import eu.darken.sdmse.automation.core.common.textMatchesAny
import eu.darken.sdmse.automation.core.common.windowCriteriaAppIdentifier
import eu.darken.sdmse.automation.core.specs.AutomationExplorer
import eu.darken.sdmse.automation.core.specs.AutomationSpec
import eu.darken.sdmse.common.device.DeviceDetective
import eu.darken.sdmse.common.datastore.value
import eu.darken.sdmse.common.debug.Bugs
import eu.darken.sdmse.common.debug.logging.Logging.Priority.INFO
import eu.darken.sdmse.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.deviceadmin.DeviceAdminManager
import eu.darken.sdmse.common.funnel.IPCFunnel
import eu.darken.sdmse.common.hasApiLevel
import eu.darken.sdmse.common.pkgs.Pkg
import eu.darken.sdmse.common.pkgs.PkgRepo
import eu.darken.sdmse.common.pkgs.features.Installed
import eu.darken.sdmse.common.pkgs.isInstalled
import eu.darken.sdmse.common.pkgs.toPkgId
import eu.darken.sdmse.common.progress.withProgress
import javax.inject.Inject


@Reusable
class MIUISpecs @Inject constructor(
    private val ipcFunnel: IPCFunnel,
    private val deviceDetective: DeviceDetective,
    private val pkgRepo: PkgRepo,
    private val miuiLabels: MIUILabels,
    private val aospLabels: AOSPLabels,
    private val deviceAdminManager: DeviceAdminManager,
    private val settings: AppControlSettings,
) : AppControlSpecGenerator {

    override val tag: String = TAG

    override suspend fun isResponsible(pkg: Installed): Boolean {
        if (settings.romTypeDetection.value() == RomType.MIUI) return true
        if (deviceDetective.isCustomROM()) return false
        if (!deviceDetective.isXiaomi()) return false
        if (VERSION_STARTS.none { Build.VERSION.INCREMENTAL.startsWith(it) }) return false
        return pkgRepo.isInstalled(SETTINGS_PKG_MIUI)
    }

    override suspend fun getSpec(pkg: Installed): AutomationSpec = object : AutomationSpec.Explorer {
        override suspend fun createPlan(): suspend AutomationExplorer.Context.() -> Unit = {
            mainPlan(pkg)
        }
    }

    private val isMiui12Plus: Boolean =
        VERSION_STARTS_CURRENT.any { Build.VERSION.INCREMENTAL.startsWith(it) } || hasApiLevel(34)

    private val mainPlan: suspend AutomationExplorer.Context.(Installed) -> Unit = { pkg ->
        log(TAG, INFO) { "Executing plan for ${pkg.installId} with context $this" }

        var windowPkg: Pkg.Id? = null

        val step = StepProcessor.Step(
            parentTag = TAG,
            label = "Opening app settings screen",
            windowIntent = defaultWindowIntent(pkg),
            windowEventFilter = { event ->
                // Some MIUI14 devices send the change event for the system settings app
                event.pkgId == SETTINGS_PKG_MIUI || event.pkgId == SETTINGS_PKG_AOSP
            },
            windowNodeTest = {
                when {
                    windowCriteriaAppIdentifier(SETTINGS_PKG_MIUI, ipcFunnel, pkg)(it) -> {
                        windowPkg = SETTINGS_PKG_MIUI
                        true
                    }

                    windowCriteriaAppIdentifier(SETTINGS_PKG_AOSP, ipcFunnel, pkg)(it) -> {
                        windowPkg = SETTINGS_PKG_AOSP
                        true
                    }

                    else -> {
                        log(TAG) { "Unknown window: ${it.pkgId}" }
                        false
                    }
                }
            },
        )
        stepper.withProgress(this) { process(step) }

        log(TAG) { "Launched window pkg was $windowPkg" }
        when (windowPkg) {
            SETTINGS_PKG_AOSP -> settingsPlan(pkg)
            SETTINGS_PKG_MIUI -> securityCenterPlan(pkg)
            else -> throw IllegalStateException("Unknown window: $windowPkg")
        }
    }

    private val settingsPlan: suspend AutomationExplorer.Context.(Installed) -> Unit = { pkg ->
        log(TAG, INFO) { "Executing AOSP settings plan for ${pkg.installId} with context $this" }

        val locale = getSysLocale()
        val lang = locale.language
        val script = locale.script

        run {
            val storageEntryLabels =
                aospLabels.getStorageEntryDynamic() + aospLabels.getStorageEntryStatic(lang, script)

            val storageFilter = onTheFlyLabler.getAOSPStorageFilter(storageEntryLabels, pkg)

            val step = StepProcessor.Step(
                parentTag = tag,
                label = "Find & click 'Storage' (targets=$storageEntryLabels)",
                nodeTest = storageFilter,
                nodeRecovery = getDefaultNodeRecovery(pkg),
                nodeMapping = clickableParent(),
                action = defaultClick()
            )
            stepper.withProgress(this) { process(step) }
        }

        run {
            val clearCacheButtonLabels =
                aospLabels.getClearCacheDynamic() + aospLabels.getClearCacheStatic(lang, script)

            val buttonFilter = fun(node: AccessibilityNodeInfo): Boolean {
                if (!node.isClickyButton()) return false
                return node.textMatchesAny(clearCacheButtonLabels)
            }

            val step = StepProcessor.Step(
                parentTag = tag,
                label = "Find & click 'Clear Cache' (targets=$clearCacheButtonLabels)",
                nodeTest = buttonFilter,
                action = getAospClearCacheClick(pkg, tag)
            )
            stepper.withProgress(this) { process(step) }
        }
    }

    private val securityCenterPlan: suspend AutomationExplorer.Context.(Installed) -> Unit = { pkg ->
        log(TAG, INFO) { "Executing MIUI security center plan for ${pkg.installId} with context $this" }

        val locale = getSysLocale()
        val lang = locale.language
        val script = locale.script
        val country = locale.country

        log(VERBOSE) { "Getting specs for ${pkg.packageName} (lang=$lang, script=$script, country=$country)" }

        val clearDataLabels = miuiLabels.getClearDataButtonLabels(lang, script, country)
        val clearCacheLabels = miuiLabels.getClearCacheButtonLabels(lang, script, country)
        val dialogTitles = miuiLabels.getDialogTitles(lang, script, country)

        var useAlternativeStep = deviceAdminManager.getDeviceAdmins().contains(pkg.id).also {
            if (it) log(TAG) { "${pkg.id} is a device admin, using alternative step directly." }
        }

        if (!useAlternativeStep) {
            val clearDataFilter: suspend (AccessibilityNodeInfo) -> Boolean = filter@{ node ->
                when {
                    // MIUI 12+
                    isMiui12Plus -> if (!node.isTextView()) return@filter false
                    // MIUI 10/11
                    else -> if (!node.isClickyButton()) return@filter false
                }

                if (node.textMatchesAny(clearDataLabels)) return@filter true

                if (node.textMatchesAny(clearCacheLabels)) {
                    useAlternativeStep = true
                    throw StepAbortException("Got 'Clear cache' instead of 'Clear data' skip the action dialog step.")
                }
                return@filter false
            }
            val step = StepProcessor.Step(
                parentTag = TAG,
                label = "Find & click MIUI 'Clear data' (targets=$clearDataLabels)",
                windowNodeTest = windowCriteriaAppIdentifier(SETTINGS_PKG_MIUI, ipcFunnel, pkg),
                nodeTest = clearDataFilter,
                nodeRecovery = getDefaultNodeRecovery(pkg),
                nodeMapping = when {
                    // MIUI 12 needs a node mapping, while in MIUI 11 the text is directly clickable.
                    isMiui12Plus -> clickableParent()
                    else -> null
                },
                action = defaultClick()
            )
            stepper.withProgress(this) { process(step) }
        }

        if (useAlternativeStep) {
            val alternativeStep: StepProcessor.Step = StepProcessor.Step(
                parentTag = TAG,
                label = "BRANCH: Find & Click 'Clear cache' (targets=$clearCacheLabels)",
                windowNodeTest = windowCriteriaAppIdentifier(SETTINGS_PKG_MIUI, ipcFunnel, pkg),
                nodeTest = when {
                    isMiui12Plus -> {
                        { it.isTextView() && it.textMatchesAny(clearCacheLabels) }
                    }

                    else -> {
                        { it.isClickyButton() && it.textMatchesAny(clearCacheLabels) }
                    }
                },
                nodeMapping = when {
                    isMiui12Plus -> clickableParent()
                    else -> null
                },
                action = defaultClick()
            )
            stepper.withProgress(this) { process(alternativeStep) }
        } else {
            // This may be skipped when MIUI just shows a 'Clear cache' option

            // Clear data
            // -> Clear data
            // -> Clear cache
            // -> Cancel

            val windowCriteria = fun(node: AccessibilityNodeInfo): Boolean {
                if (node.pkgId != SETTINGS_PKG_MIUI) return false
                return node.crawl().map { it.node }.any { it.idContains("id/alertTitle") }
            }
            val entryFilter = fun(node: AccessibilityNodeInfo): Boolean {
                if (!node.isClickable || !node.isTextView()) return false
                return node.textMatchesAny(clearCacheLabels)
            }

            val step = StepProcessor.Step(
                parentTag = TAG,
                label = "Find & click 'Clear Cache' entry in bottom sheet (targets=$clearCacheLabels)",
                windowNodeTest = windowCriteria,
                nodeTest = entryFilter,
                action = defaultClick()
            )
            stepper.withProgress(this) { process(step) }
        }

        // Clear cache?
        // -> Cancel        -> Ok
        run {
            val windowCriteria = fun(node: AccessibilityNodeInfo): Boolean {
                if (node.pkgId != SETTINGS_PKG_MIUI) return false
                return node.crawl().map { it.node }.any { subNode ->
                    // This is required to relax the match on the dialog texts
                    // Otherwise it could detect the clear cache button as match
                    if (!subNode.idContains("id/alertTitle")) return@any false
                    return@any when {
                        subNode.textMatchesAny(dialogTitles) -> true
                        subNode.textMatchesAny(dialogTitles.map { it.replace("?", "") }) -> true
                        subNode.textMatchesAny(dialogTitles.map { "$it?" }) -> true
                        subNode.textEndsWithAny(clearCacheLabels.map { "$it?" }) -> true
                        subNode.textEndsWithAny(clearCacheLabels) -> true
                        else -> false
                    }
                }
            }

            val buttonFilter = fun(node: AccessibilityNodeInfo): Boolean {
                if (!node.isClickyButton()) return false
                return when (Bugs.isDryRun) {
                    true -> node.idMatches("android:id/button2")
                    false -> node.idMatches("android:id/button1")
                }
            }


            val step = StepProcessor.Step(
                parentTag = TAG,
                label = "Find & click 'OK' in confirmation dialog",
                windowNodeTest = windowCriteria,
                nodeTest = buttonFilter,
                action = defaultClick()
            )
            stepper.withProgress(this) { process(step) }
        }
    }

    @Module @InstallIn(SingletonComponent::class)
    abstract class DIM {
        @Binds @IntoSet abstract fun mod(mod: MIUISpecs): AppControlSpecGenerator
    }

    companion object {
        val TAG: String = logTag("AppControl", "Automation", "MIUI", "Specs")
        private val SETTINGS_PKG_MIUI = "com.miui.securitycenter".toPkgId()
        private val SETTINGS_PKG_AOSP = "com.android.settings".toPkgId()
        private val VERSION_STARTS_LEGACY = arrayOf(
            "V10",
            "V11",
        )
        private val VERSION_STARTS_CURRENT = arrayOf(
            // Xiaomi/raphael_eea/raphael:10/QKQ1.190825.002/V12.0.1.0.QFKEUXM:user/release-keys
            "V12",
            // Xiaomi/venus_eea/venus:12/SKQ1.211006.001/V13.0.1.0.SKBEUXM:user/release-keys
            "V13",
            // Xiaomi/plato_id/plato:13/TP1A.220624.014/V14.0.1.0.TLQIDXM:user/release-keys
            "V14",
            // POCO/mondrian_global/mondrian:14/UKQ1.230804.001/V816.0.1.0.UMNMIXM:user/release-keys,
            "V816", // wtf poco
        )
        private val VERSION_STARTS = VERSION_STARTS_LEGACY + VERSION_STARTS_CURRENT
    }

}