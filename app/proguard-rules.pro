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
