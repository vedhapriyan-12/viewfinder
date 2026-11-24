# Dot-source in a PowerShell session before building:  . D:\viewfinder\env.ps1
# Session-only; changes no persistent machine/user settings.
$env:JAVA_HOME   = "D:\Android\jdk\jdk-21.0.13.1+2"
$env:ANDROID_HOME = "D:\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;D:\Android\gradle\gradle-9.9.2\bin;$env:Path"
