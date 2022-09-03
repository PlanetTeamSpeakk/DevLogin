package com.ptsmods.devlogin.mixin;

import com.ptsmods.devlogin.DevLogin;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Main.class)
public class MixinMain {

    /**
     * Modify the args array whenever it is loaded.
     * This works both in older and modern versions (1.19.1+ or something along those lines)
     * where there are two main methods (one has another arg, a boolean)
     * @param args The args to modify
     * @return Modified version of the passed args
     */
    @ModifyVariable(at = @At("LOAD"), method = "main([Ljava/lang/String;)V", argsOnly = true)
    private static String[] modifyArgs(String[] args) {
        return DevLogin.modifyArgs(args);
    }
}
