package kuko.ui

import arc.scene.ui.Dialog
import arc.util.Reflect
import arc.util.Time
import kuko.net.ProxyListener
import mindustry.Vars
import mindustry.ui.dialogs.JoinDialog

class JoinDialogPatched : JoinDialog() {
    override fun connect(ip: String, port: Int) {
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

            Vars.net.connect("localhost", localPort) {
                if (Vars.net.client()) {
                    hide()
                    Reflect.get<Dialog>(JoinDialog::class.java, this, "add").hide()
                }
            }
        }
    }
}