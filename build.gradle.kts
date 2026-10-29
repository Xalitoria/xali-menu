import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad

plugins {
    kotlin("plugin.serialization")
    id("mc.xalitoria.minecraft")
    alias(libs.plugins.shadow)
}

subprojects {
    group = "mc.xalitoria"
    version = "0.0.1"
}

paper {
    name = "XaliMenu"
    main = "mc.xalitoria.menu.paper.XaliMenu"
    prefix = "XaliMenu"
    author = "audizian"
    website = "https://xalitoria.com"
    apiVersion = "26.2"
    load = Load.POSTWORLD
    foliaSupported = true
    relocateSources = true
    serverDependencies {
        register("PlaceholderAPI") { load = RelativeLoad.BEFORE }
        // The economy, item and skin providers load first so the menu finds them when it enables.
        register("Vault") { load = RelativeLoad.BEFORE; required = false }
        register("VaultUnlocked") { load = RelativeLoad.BEFORE; required = false }
        register("ItemsAdder") { load = RelativeLoad.BEFORE; required = false }
        register("Nexo") { load = RelativeLoad.BEFORE; required = false }
        register("SkinsRestorer") { load = RelativeLoad.BEFORE; required = false }
        register("HeadDatabase") { load = RelativeLoad.BEFORE; required = false }
    }
    // ItemsAdder, Nexo, SkinsRestorer and HeadDatabase aren't downloadable without an account: put their jars in the
    // server's plugins by hand.
    server {
        plugin("PlaceholderAPI-2.12.3.jar", "https://cdn.modrinth.com/data/lKEzGugV/versions/pIvQcXW8/PlaceholderAPI-2.12.3.jar")
        plugin("VaultUnlocked-2.20.3.jar", "https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar")
    }
}
