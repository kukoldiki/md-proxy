package kuko

import arc.Events
import arc.util.Log
import kuko.ui.JoinDialogPatched
import mindustry.Vars
import mindustry.game.EventType
import mindustry.io.JsonIO
import mindustry.mod.Mod

class ProxyMod : Mod() {
    init {
        /*val proxy = SocksProxy("127.0.0.1", 1081, "node2.larzed.icu", 6568, 5)
        proxy.block()
        val tcp = proxy.getTcp()
        val udp = proxy.getUdp()
        if(tcp != null) {
            Log.info("TCP: Ok")
        }
        if(udp != null) {
            Log.info("UDP: Ok Proxied: ${udp.proxied}")
        }*/

        Events.on(EventType.ClientLoadEvent::class.java) { _ ->
            val old = Vars.ui.join
            Vars.ui.join = JoinDialogPatched()
            JsonIO.json.copyFields(old, Vars.ui.join)

            Log.info("JoinDialog Successfuly patched!!!")
        }
    }

    override fun loadContent() {
        Log.info("Loading content")
    }
}