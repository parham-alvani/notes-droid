# jlatexmath loads its fonts and symbol tables reflectively from resources.
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }

# commonmark uses no reflection; Room, Hilt and kotlinx-serialization ship
# their own consumer rules.

# JGit and Apache MINA sshd resolve implementations through ServiceLoader and
# reflection, so R8 cannot see the wiring and would strip it.
-keep class org.eclipse.jgit.** { *; }
-keep class org.apache.sshd.** { *; }
-keepclassmembers class * implements org.eclipse.jgit.transport.SshSessionFactory { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.apache.sshd.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**

# Apache sshd finds the Ed25519 implementation by name, not by symbol:
# SecurityUtils asks for the string "net.i2p.crypto.eddsa.EdDSASecurityProvider"
# and registers whatever comes back. R8 cannot see a reference that only exists
# as a string, so it renames the package and the lookup quietly fails -- sshd
# then reports Ed25519 as unsupported, and the app can neither read the key it
# generated nor offer it to the server.
#
# The failure looks nothing like its cause. The transport reports
# "publickey: no keys to try" and the key's own fingerprint comes back
# "unreadable", which reads as a key the server refused rather than a library
# that is no longer there -- and cost a perfectly good deploy key before it was
# understood. Only release builds are affected, because only they run R8.
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
