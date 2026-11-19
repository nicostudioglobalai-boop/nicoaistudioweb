# SwiftVault-PC

Desktop companion for SwiftVault. Organises a projects folder by type and year-month, compresses files older than 90 days into ZIPs inside `Archive/`, finds exact duplicates (MD5-verified) into `duplicates-report.txt`, and reports free space.

## Install Java (once)
Download and install from https://adoptium.net/temurin/releases (choose Windows x64, .msi).

## Run
Double-click `SwiftVault-PC.jar`, or from a terminal:

```
java -jar SwiftVault-PC.jar
```

Headless / batch mode:

```
java -jar SwiftVault-PC.jar "C:\path\to\projects" 90
```

Self-test (no GUI needed):

```
java -jar SwiftVault-PC.jar --selftest
```

## Build from source

```
javac --release 8 -d out DesktopVault.java
jar cfe SwiftVault-PC.jar DesktopVault -C out .
```
