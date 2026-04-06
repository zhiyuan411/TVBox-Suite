package com.github.tvbox.osc.util.js;

import com.google.common.util.concurrent.SettableFuture;
import com.whl.quickjs.wrapper.JSCallFunction;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;

public class Async {

    private final SettableFuture<Object> future;

    public static SettableFuture<Object> run(JSObject object, String name, Object[] args) {
        return new Async().call(object, name, args);
    }

    private Async() {
        this.future = SettableFuture.create();
    }

    private SettableFuture<Object> call(JSObject object, String name, Object[] args) {
        try {
            // 防御性编程：检查必要对象
            if (object == null) {
                future.set(null);
                return future;
            }
            if (name == null || name.isEmpty()) {
                future.set(null);
                return future;
            }
            
            JSFunction function = object.getJSFunction(name);
            if (function == null) {
                future.set(null);
                return future;
            }
            
            // 防御性编程：检查参数
            if (args == null) {
                args = new Object[0];
            }
            
            Object result = function.call(args);
            if (result instanceof JSObject) {
                then(result);
            } else {
                future.set(result);
            }
        } catch (com.whl.quickjs.wrapper.QuickJSException e) {
            // 特殊处理 QuickJSException，避免 JNI 层异常，直接返回 null
            future.set(null);
        } catch (Throwable t) {
            // 捕获所有其他异常，避免崩溃
            future.set(null);
        }
        return future;
    }

    private void then(Object result) {
        try {
            if (result == null) {
                future.set(null);
                return;
            }
            JSObject promise = (JSObject) result;
            JSFunction thenFn = promise.getJSFunction("then");
            if (thenFn != null) {
                thenFn.call(callback);
            } else {
                // If there's no then, complete immediately
                future.set(result);
            }
        } catch (Throwable t) {
            // 捕获所有异常，避免 Promise 处理时导致崩溃
            future.set(null);
        }
    }

    private final JSCallFunction callback = new JSCallFunction() {
        @Override
        public Object call(Object... args) {
            try {
                // args[0] holds the resolved value from the JS promise
                future.set(args.length > 0 ? args[0] : null);
            } catch (Throwable t) {
                // 捕获所有异常，避免 Promise 回调时导致崩溃
                future.set(null);
            }
            return null;
        }
    };
}
