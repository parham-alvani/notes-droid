# jlatexmath loads its fonts and symbol tables reflectively from resources.
-keep class ru.noties.jlatexmath.** { *; }
-keep class org.scilab.forge.jlatexmath.** { *; }

# commonmark uses no reflection; Room, Hilt and kotlinx-serialization ship
# their own consumer rules.
