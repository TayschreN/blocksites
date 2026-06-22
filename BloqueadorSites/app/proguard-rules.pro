# Mantém todas as classes do app (VPN, admin, receivers precisam de nomes reais)
-keep class com.bloqueador.sites.** { *; }
-keep class com.bloqueador.sites.DeviceAdminReceiver { *; }
-keep class com.bloqueador.sites.BlockerVpnService   { *; }
-keep class com.bloqueador.sites.BootReceiver        { *; }
