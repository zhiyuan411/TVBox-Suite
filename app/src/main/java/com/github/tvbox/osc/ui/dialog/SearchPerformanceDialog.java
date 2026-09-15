package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.widget.OnItemClickListener;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

/**
 * 搜索性能设置弹窗：5 个选项（搜索并发数 / 插件搜索并发数 / 总并发上限 / 单域名并发上限 / 快速搜索批次大小）。
 *
 * 弹窗样式参考「播放器设置」(MediaSettingDialog)，但内容统一在右侧单列展示，
 * 每项弹出一个 SelectDialog 让用户从预定义数值中选择。
 *
 * 生效说明：
 * - 搜索并发数 / 插件搜索并发数 / 批次大小：写入后下一次搜索即时生效。
 * - 总并发上限 / 单域名并发上限：写入 OkHttp Dispatcher 仅在进程启动时生效，需重启 App。
 */
public class SearchPerformanceDialog extends BaseDialog {

    public SearchPerformanceDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_search_performance);
        setCanceledOnTouchOutside(true);
        TvRecyclerView list = findViewById(R.id.list_search_performance);
        SearchPerformanceAdapter adapter = new SearchPerformanceAdapter();
        list.setAdapter(adapter);
        adapter.setNewData(buildItems());

        list.setOnItemListener((OnItemClickListener) (tvRecyclerView, view, position) -> {
            FastClickCheckUtil.check(view);
            Item item = adapter.getItem(position);
            if (item == null) return;
            openOptionPicker(item, adapter, position);
        });
    }

    private void openOptionPicker(Item item, SearchPerformanceAdapter adapter, int position) {
        ArrayList<Integer> options = item.options;
        int current = item.current();
        int defaultPos = options.indexOf(current);
        if (defaultPos < 0) defaultPos = 0;

        SelectDialog<Integer> dialog = new SelectDialog<>(getContext());
        dialog.setTip(item.title);
        dialog.setAdapter(null, new SelectDialogAdapter.SelectDialogInterface<Integer>() {
            @Override
            public void click(Integer value, int pos) {
                item.save.accept(value);
                // 通知整个 list 刷新，避免其它位置因为持有旧 current 值而被错选
                adapter.setNewData(buildItems());
                adapter.notifyItemChanged(position);
            }

            @Override
            public String getDisplay(Integer val) {
                return String.valueOf(val);
            }
        }, new DiffUtil.ItemCallback<Integer>() {
            @Override
            public boolean areItemsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                return oldItem.intValue() == newItem.intValue();
            }

            @Override
            public boolean areContentsTheSame(@NonNull @NotNull Integer oldItem, @NonNull @NotNull Integer newItem) {
                return oldItem.intValue() == newItem.intValue();
            }
        }, options, defaultPos);
        dialog.show();
    }

    private ArrayList<Item> buildItems() {
        ArrayList<Item> items = new ArrayList<>();
        // 选项从「等于默认值的代表值」起步，按"差异化、有代表性、覆盖高性能手机档位"原则挑选 7~8 个值，
        // 不再枚举区间内所有整数值。范围由 HawkConfig 的 MAX_* 兜底。
        items.add(new Item(
                getContext().getString(R.string.mn_search_thread),
                HawkConfig::getSearchThreadCount,
                HawkConfig::putSearchThreadCount,
                OPTIONS_SEARCH_THREAD));
        items.add(new Item(
                getContext().getString(R.string.mn_spider_thread),
                HawkConfig::getSearchSpiderConcurrency,
                HawkConfig::putSearchSpiderConcurrency,
                OPTIONS_SPIDER_THREAD));
        items.add(new Item(
                getContext().getString(R.string.mn_max_requests) + suffixIfRestartRequired(true),
                HawkConfig::getOkhttpMaxRequests,
                HawkConfig::putOkhttpMaxRequests,
                OPTIONS_MAX_REQUESTS));
        items.add(new Item(
                getContext().getString(R.string.mn_max_requests_per_host) + suffixIfRestartRequired(true),
                HawkConfig::getOkhttpMaxRequestsPerHost,
                HawkConfig::putOkhttpMaxRequestsPerHost,
                OPTIONS_MAX_REQUESTS_PER_HOST));
        items.add(new Item(
                getContext().getString(R.string.mn_batch_size),
                HawkConfig::getSearchBatchSize,
                HawkConfig::putSearchBatchSize,
                OPTIONS_BATCH_SIZE));
        return items;
    }

    /** 给需要重启的选项追加 "（需重启生效）" 提示，避免误以为保存后立即生效。 */
    private String suffixIfRestartRequired(boolean append) {
        if (!append) return "";
        return "  (" + getContext().getString(R.string.hint_restart_required) + ")";
    }

    /** 5 项配置的代表选值（按需调整，保持差异化即可）。默认值为数组第一个元素。 */
    private static final ArrayList<Integer> OPTIONS_SEARCH_THREAD = buildFromArray(
            5, 8, 16, 32);                               // 默认 5，上限 32（原 20 的 1.6 倍）
    private static final ArrayList<Integer> OPTIONS_SPIDER_THREAD = buildFromArray(
            5, 8, 16, 32);                               // 默认 5，上限 32（原 20 的 1.6 倍）
    private static final ArrayList<Integer> OPTIONS_MAX_REQUESTS = buildFromArray(
            64, 128, 256, 512);                          // 默认 64，上限 512（原 256 的 2 倍）
    private static final ArrayList<Integer> OPTIONS_MAX_REQUESTS_PER_HOST = buildFromArray(
            5, 8, 16, 32, 64);                           // 默认 5，上限 64（原 32 的 2 倍）
    private static final ArrayList<Integer> OPTIONS_BATCH_SIZE = buildFromArray(
            50, 100, 200, 500, 1000);                    // 默认 50，上限 1000（原 200 的 5 倍）

    private static ArrayList<Integer> buildFromArray(int... values) {
        ArrayList<Integer> list = new ArrayList<>(values.length);
        for (int v : values) list.add(v);
        return list;
    }

    /** 选项数据：title + 当前值读取器 + 保存回调 + 可选数值列表 */
    static class Item {
        final String title;
        final IntSupplier current;
        final IntConsumer save;
        final ArrayList<Integer> options;

        Item(String title, IntSupplier current,
             IntConsumer save, ArrayList<Integer> options) {
            this.title = title;
            this.current = current;
            this.save = save;
            this.options = options;
        }

        int current() {
            return current.get();
        }
    }

    /** 最小化的整型读写接口，避免依赖 java.util.function（minSdk 21 不支持）。 */
    interface IntSupplier {
        int get();
    }

    /** 最小化的整型消费接口，避免依赖 java.util.function（minSdk 21 不支持）。 */
    interface IntConsumer {
        void accept(int value);
    }

    /** 选项列表 Adapter：复用现有的 item_dialog_select2（标题+当前值+">"），参考 MediaSettingContentAdapter。 */
    public static class SearchPerformanceAdapter
            extends BaseQuickAdapter<Item, BaseViewHolder> {

        public SearchPerformanceAdapter() {
            super(R.layout.item_dialog_select2);
        }

        @Override
        protected void convert(@NonNull BaseViewHolder helper, Item item) {
            TextView tvTitle = helper.getView(R.id.tv_title);
            tvTitle.setText(item.title);
            TextView tvContent = helper.getView(R.id.tv_content);
            tvContent.setText(String.valueOf(item.current()));
        }
    }
}