# MediRoute

Constraint-based emergency ambulance allocation. Plain Java (8 or newer), no dependencies.

## Run (Windows)
Double-click run.bat, or in a terminal:
    java -jar target\mediroute.jar
Then open http://localhost:8080

## Rebuild from source (needs a JDK, not just a JRE)
    mkdir out
    javac -d out src\main\java\com\mediroute\*.java
    xcopy /E /I src\main\resources\static out\static
    java -cp out com.mediroute.Main
