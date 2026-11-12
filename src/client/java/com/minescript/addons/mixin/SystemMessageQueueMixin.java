package com.minescript.addons.mixin;

import com.minescript.addons.client.MinescriptErrorHandler;
import net.minescript.common.SystemMessageQueue;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SystemMessageQueue.class)
public abstract class SystemMessageQueueMixin {
    @Inject(method = "logUserError(Ljava/lang/String;[Ljava/lang/Object;)V", at = @At("HEAD"))
    private void onLogUserError(String messagePattern, Object[] arguments, CallbackInfo ci) {
        String formatted = (arguments.length == 0)
            ? messagePattern
            : ParameterizedMessage.format(messagePattern, arguments);
        MinescriptErrorHandler.onSystemError(formatted);
    }

    @Inject(method = "logUserInfo(Ljava/lang/String;[Ljava/lang/Object;)V", at = @At("HEAD"))
    private void onLogUserInfo(String messagePattern, Object[] arguments, CallbackInfo ci) {
        String formatted = (arguments.length == 0)
            ? messagePattern
            : ParameterizedMessage.format(messagePattern, arguments);
        MinescriptErrorHandler.onSystemInfo(formatted);
    }
}
