package kuko

import arc.Core
import arc.Core.bundle
import arc.Events
import arc.util.Log
import kuko.CVars.socksVersion
import kuko.ui.JoinDialogPatched
import mindustry.Vars
import mindustry.game.EventType
import mindustry.io.JsonIO
import mindustry.mod.Mod
import mindustry.ui.dialogs.BaseDialog

class ProxyMod : Mod() {
    init {
        Events.on(EventType.ClientLoadEvent::class.java) { _ ->
            val old = Vars.ui.join
            Vars.ui.join = JoinDialogPatched()
            JsonIO.json.copyFields(old, Vars.ui.join)
            Log.info("JoinDialog Successfuly patched!!!")

            CVars.socksVersion = Core.settings.getInt("socksversion")
            CVars.proxyPort = Core.settings.getString("proxyport").toInt()

            Vars.ui.settings.addCategory("proxy") { b ->
                b.checkPref("useproxy", false)
                b.textPref("proxyip", "localhost")
                b.textPref("proxyport", "1080") {
                    val port = it.toIntOrNull()
                    if(port == null) {
                        val dialog = BaseDialog("invalidport")
                        dialog.cont.add(bundle.get("error.invalidport")).row()
                        dialog.closeOnBack()
                        dialog.cont.button("@close") {
                            dialog.hide()
                        }
                        dialog.show()
                        Core.settings.put("proxyport", "1080")
                        CVars.proxyPort = 1080
                        return@textPref
                    }
                    CVars.proxyPort = port
                }
                /*b.textPref("socksversion", "5") {
                    val version = it.toIntOrNull()

                    if (version == null || version !in listOf(4, 5)) {
                        val dialog = BaseDialog("invalidversion")
                        dialog.cont.add(bundle.get("error.invalidversion")).row()
                        dialog.closeOnBack()
                        dialog.cont.button("@close") {
                            dialog.hide()
                        }
                        dialog.show()
                        Core.settings.put("socksversion", "5")
                        CVars.socksVersion = 5
                        return@textPref
                    }

                    CVars.socksVersion = version
                }*/
                b.sliderPref("socksversion", 5, 4, 5, 1) { ver ->
                    CVars.socksVersion = ver
                    return@sliderPref bundle.format("slider.version", ver)
                }
            }
        }
    }

    override fun loadContent() {
        Log.info("Loading content")
    }
}