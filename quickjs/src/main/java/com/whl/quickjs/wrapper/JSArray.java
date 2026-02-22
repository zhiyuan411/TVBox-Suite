package com.whl.quickjs.wrapper;

import org.json.JSONArray;

public class JSArray extends JSObject {

    public JSArray(QuickJSContext context, long pointer) {
        super(context, pointer);
    }

    public int length() {
        checkReleased();
        try {
            return getContext().length(this);
        } catch (Throwable t) {
            return 0;
        }
    }

    public Object get(int index) {
        checkReleased();
        try {
            // 防御性编程：检查索引范围
            int len = length();
            if (index < 0 || index >= len) {
                return null;
            }
            return getContext().get(this, index);
        } catch (Throwable t) {
            return null;
        }
    }

    public JSArray set(Object value, int index) {
        checkReleased();
        try {
            // 防御性编程：检查索引范围
            int len = length();
            if (index < 0 || index >= len) {
                return this;
            }
            getContext().set(this, value, index);
        } catch (Throwable t) {
            // 捕获异常，防止崩溃
        }
        return this;
    }


    public JSArray push(Object value) {
        checkReleased();
        try {
            getContext().arrayAdd(this, value);
        } catch (Throwable t) {
            // 捕获异常，防止崩溃
        }
        return this;
    }

    public JSONArray toJSONArray() {
        JSONArray jsonArray = new JSONArray();
        try {
            int len = length();
            for (int i = 0; i < len; i++) {
                try {
                    Object obj = this.get(i);
                    if (obj == null || obj instanceof JSFunction) {
                        continue;
                    }
                    if (obj instanceof Number || obj instanceof String || obj instanceof Boolean) {
                        jsonArray.put(obj);
                    } else if (obj instanceof JSArray) {
                        jsonArray.put(((JSArray) obj).toJSONArray());
                    } else if (obj instanceof JSObject) {
                        try {
                            jsonArray.put(((JSObject) obj).toJSONObject());
                        } catch (Throwable t) {
                            // 跳过无法转换的对象
                        }
                    }
                } catch (Throwable t) {
                    // 跳过处理失败的元素
                }
            }
        } catch (Throwable t) {
            // 捕获所有异常，返回空数组
        }
        return jsonArray;
    }

    public String toJsonString() {
        try {
            return getContext().stringify(this);
        } catch (Throwable t) {
            return "[]";
        }
    }

    public JSONArray toJsonArray() {
        JSONArray jsonArray = new JSONArray();
        try {
            int len = length();
            for (int i = 0; i < len; i++) {
                try {
                    Object obj = this.get(i);
                    if (obj == null || obj instanceof JSFunction) {
                        continue;
                    }
                    if (obj instanceof Number || obj instanceof String || obj instanceof Boolean) {
                        jsonArray.put(obj);
                    } else if (obj instanceof JSArray) {
                        jsonArray.put(((JSArray) obj).toJsonArray());
                    } else if (obj instanceof JSObject) {
                        try {
                            jsonArray.put(((JSObject) obj).toJsonObject());
                        } catch (Throwable t) {
                            // 跳过无法转换的对象
                        }
                    }
                } catch (Throwable t) {
                    // 跳过处理失败的元素
                }
            }
        } catch (Throwable t) {
            // 捕获所有异常，返回空数组
        }
        return jsonArray;
    }
}
