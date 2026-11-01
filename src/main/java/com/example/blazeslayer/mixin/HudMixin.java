package com.example.blazeslayer.mixin;

import com.example.blazeslayer.BlazeSlayerMod;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Hypixel's on-screen attunement text (the title/subtitle shown after a dagger swap)
 * from ever being stored or drawn. Since Minecraft 26.2 the title lives in Hud, not Gui.
 */
@Mixin(Hud.class)
public class HudMixin {

    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true, require = 0)
    private void blazeslayer$hideTitle(Component title, CallbackInfo ci) {
        if (BlazeSlayerMod.shouldHideSwapText(title, "title")) ci.cancel();
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true, require = 0)
    private void blazeslayer$hideSubtitle(Component subtitle, CallbackInfo ci) {
        if (BlazeSlayerMod.shouldHideSwapText(subtitle, "subtitle")) ci.cancel();
    }
}
