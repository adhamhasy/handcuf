# Kidnap Mod (Fabric 26.3)
Server: this jar + Fabric API 0.161.0+26.3 (+ ViaFabric/ViaVersion for older clients).
Players: no mod needed. Optional: install the same jar (+ Mod Menu) on your 26.3 client for the settings screen + creative tab,
and KidnapMod-ResourcePack.zip for textures.
Build: ./gradlew build (JDK 25) -> build/libs/kidnapmod-1.0.0.jar (not -sources).

Commands: /kidnapmod give <item> | free <player> | config <key> [value] | config reset   (ops)
          /kidnapmod pref <key> <value> | sync                                         (everyone)
Config files: config/kidnapmod-server.json, kidnapmod-prefs.json, kidnapmod-client.json
