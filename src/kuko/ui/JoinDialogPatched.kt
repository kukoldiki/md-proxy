package kuko.ui

import arc.Core
import arc.math.Rand
import arc.scene.ui.Dialog
import arc.util.Reflect
import arc.util.Time
import arc.util.serialization.Base64Coder
import kuko.net.ProxyListener
import mindustry.Vars
import mindustry.ui.dialogs.JoinDialog

class JoinDialogPatched : JoinDialog() {
    override fun connect(ip: String, port: Int) {
        if(!Core.settings.getBool("useproxy")) {
            super.connect(ip, port)
            return
        }
        if (Vars.player.name.trim { it <= ' ' }.isEmpty()) {
            Vars.ui.showInfo("@noname")
            return
        }

        Vars.ui.loadfrag.show("@connecting")

        Vars.ui.loadfrag.setButton(Runnable {
            Vars.ui.loadfrag.hide()
            Vars.netClient.disconnectQuietly()
        })

        Vars.ui.editor.hide()

        Time.runTask(2f) {
            Vars.logic.reset()
            Vars.net.reset()
            Vars.netClient.beginConnecting()

            Reflect.set(JoinDialog::class.java, this, "lastIp", ip)
            Reflect.set(JoinDialog::class.java, this, "lastPort", port)

            val localPort = ProxyListener.listen(ip, port)
            Core.settings.put(
                "usid-$ip:$port",
                getUsid("$ip:$port")
            )

            Vars.net.connect("localhost", localPort) {
                if (Vars.net.client()) {
                    hide()
                    Reflect.get<Dialog>(JoinDialog::class.java, this, "add").hide()
                }
            }
        }
    }
}

fun getUsid(ip: String): String? {
    //consistently use the latter part of an IP, if possible
    var ip = ip
    if (ip.contains("/")) {
        ip = ip.substring(ip.indexOf("/") + 1)
    }

    if (Core.settings.getString("usid-$ip", null) != null) {
        return Core.settings.getString("usid-$ip", null)
    } else {
        val bytes = ByteArray(8)
        Rand().nextBytes(bytes)
        val result = String(Base64Coder.encode(bytes))
        Core.settings.put("usid-$ip", result)
        return result
    }
}