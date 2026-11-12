package com.minescript.addons.mixin;

import com.minescript.addons.client.MinescriptErrorHandler;
import net.minescript.common.Job;
import net.minescript.common.ScriptConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Job.class)
public abstract class JobMixin {
    @Shadow
    protected int jobId;

    @Shadow
    protected ScriptConfig.BoundCommand command;

    @Inject(method = "processStderr(Ljava/lang/String;)V", at = @At("HEAD"))
    private void onProcessStderr(String text, CallbackInfo ci) {
        MinescriptErrorHandler.onJobStderr(jobId, command.command(), text);
    }

    @Inject(method = "logJobException(Ljava/lang/Exception;)V", at = @At("HEAD"))
    private void onLogJobException(Exception e, CallbackInfo ci) {
        MinescriptErrorHandler.onJobException(jobId, command.command(), e);
    }
}
