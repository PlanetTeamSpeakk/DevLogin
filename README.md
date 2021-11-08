# DevLogin
Login with your own Minecraft account in a Fabric mod development environment.  
This works on all Minecraft versions supported by Fabric, ranging from 1.14 to the latest snapshot.

**Currently does not support MSA as that's extremely hard to do, just look at the effort you have to go through for it in MultiMC.**

## Usage
To use this mod, add 
```gradle
modImplementation "com.ptsmods:devlogin:1.2"
```
to your dependencies in your build.gradle file, then you can edit your run configuration in your IDE and add 
```
--username <YourUsernameOrEmail> --password <YourPassword>
``` 
to the program arguments.  

If you don't like putting your password in such a vulnerable place, you can also set the `MinecraftUsername` and `MinecraftPassword` environment variables.  
On Windows this can be done by doing:  
1. Search `Environment variables` in the Windows search bar and click it.
2. Click `Environment Variables...`
3. Set the `MinecraftUsername` and `MinecraftPassword` variables either for your user or as system variables.  

The `--username` and `--password` program arguments always take higher priority over the environment variables.

If you don't want your password stored anywhere potentionally unsafe and don't mind not being able to log onto servers or just simply wish to pretend to be some famous YouTuber or Mojang employee or whatever, you can add the `--mimicPlayer <PlayerName or UUID>` program argument instead.   
Both methods yield more or less the same results, the latter just doesn't actually login so you cannot join online servers.
