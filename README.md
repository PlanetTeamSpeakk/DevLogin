# DevLogin
Login with your own Minecraft account in a Fabric mod development environment.  
This works on all Minecraft versions supported by Fabric, ranging from 1.14 to the latest snapshot.

## Usage
To use this mod, add 
```gradle
modImplementation "com.ptsmods:devlogin:1.0.0"
```
to your dependencies in your build.gradle file, then you can edit your run configuration in your IDE and add 
```
--username <YourUsernameOrEmail> --password <YourPassword>
``` 
to the program arguments.  

If you don't like putting your password in such a vulnerable place and don't mind not being able to log onto servers or just simply wish to pretend to be some famous YouTuber or Mojang employee or whatever, you can add `--mimicPlayer <PlayerName or UUID>` instead.   
Both methods yield more or less the same results, the latter just doesn't actually login so you cannot join online servers.
