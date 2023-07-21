# DevLogin
Login with your own Minecraft account in a mod development environment.  
**This works on all Minecraft versions with any mod loader.** (In case of Forge, only on versions supporting Mixins: 1.15.2+)

## Usage
To use this mod, add 
```gradle
repositories {
  mavenCentral()
}

dependencies {
  modImplementation "com.ptsmods:devlogin:3.3.2" // For Fabric and Quilt
  implementation fg.deobf("com.ptsmods:devlogin:3.3.2") // For Forge
}
```
to your build.gradle file.  

After that, the procedure differs for different account types. You have the following options:
1. [Mojang account](#mojang-account)
2. [Microsoft account](#microsoft-account)
3. [Mimicking](#mimicking)

### Mojang account
If you have a plain old Mojang account, edit your run configuration in your IDE and add 
```
--username <YourUsernameOrEmail> --password <YourPassword>
``` 
to the program arguments.

If you don't like putting your password in such a vulnerable place, you can also set the `MinecraftUsername` and `MinecraftPassword` environment variables.  
On Windows this can be done by doing the following:  
1. Search `Environment variables` in the Windows search bar and click it.
2. Click `Environment Variables...`
3. Set the `MinecraftUsername` and `MinecraftPassword` variables either for your user or as system variables.  

The `--username` and `--password` program arguments always take higher priority over the environment variables.

### Microsoft account
If you have Migrated your Minecraft account to a Microsoft account, you can still use this mod just fine. The procedure is just a little more complicated.  

1. Add either the `--msa` or `--msa-nostore` program argument to your run configuration and launch the configuration. Use `--msa-nostore` to prevent DevLogin from storing your refresh token which can be used indefinitely to get new Minecraft tokens.
2. In a moment, you should see a dialog asking you to fill in a code on a website. Follow these instructions.
3. The dialog should disappear and Minecraft should start a moment later, logged in on your Minecraft account. This dialog can be disabled, however, by passing the `--msa-no-dialog` program argument. In this case the code is printed to the console which may be desired if you have issues with the dialog.
4. That's it, if the Minecraft token expires, a new one will be obtained using the refresh token unless `--msa-nostore` was passed instead of `--msa` in which case you'll have to redo this procedure.

### Mimicking
If you don't want your password or tokens stored anywhere potentially unsafe and don't mind not being able to log onto servers or just simply wish to pretend to be some famous YouTuber or Mojang employee or whatever, you can add the `--mimicPlayer <PlayerName or UUID>` program argument instead.   
This yields more or less the same result, mimicking just doesn't actually log in, so you cannot join online servers.
