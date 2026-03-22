package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.BounceInterpolator;// 添加选中放大效果
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.catvod.crawler.JsLoader;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.adapter.FastListAdapter;
import com.github.tvbox.osc.ui.adapter.FastSearchAdapter;
import com.github.tvbox.osc.ui.adapter.SearchWordAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.MemoryMonitor;
import com.github.tvbox.osc.util.SearchHelper;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import android.os.SystemClock;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class FastSearchActivity extends BaseActivity {
    private LinearLayout llLayout;
    private TextView mSearchTitle;
    private TvRecyclerView mGridView;
    private TvRecyclerView mGridViewFilter;
    private TvRecyclerView mGridViewWord;
    private TvRecyclerView mGridViewWordFenci;
    SourceViewModel sourceViewModel;

    private SearchWordAdapter searchWordAdapter;
    private FastSearchAdapter searchAdapter;
    private FastSearchAdapter searchAdapterFilter;
    private FastListAdapter spListAdapter;
    private String searchTitle = "";
    private HashMap<String, String> spNames;
    private boolean isFilterMode = false;
    private String searchFilterKey = "";    // 过滤的key
    private HashMap<String, ArrayList<Movie.Video>> resultVods; // 搜索结果
    private int finishedCount = 0;
    private final List<String> quickSearchWord = new ArrayList<>();
    private HashMap<String, String> mCheckSources = null;

    private final View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View itemView, boolean hasFocus) {
            try {
                if (!hasFocus) {
                    spListAdapter.onLostFocus(itemView);
                } else {
                    int ret = spListAdapter.onSetFocus(itemView);
                    if (ret < 0) return;
                    TextView v = (TextView) itemView;
                    String sb = v.getText().toString();
                    filterResult(sb);
                }
            } catch (Exception e) {
                Toast.makeText(FastSearchActivity.this, e.toString(), Toast.LENGTH_SHORT).show();
            }
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_fast_search;
    }

    @Override
    protected void init() {
        spNames = new HashMap<String, String>();
        resultVods = new HashMap<String, ArrayList<Movie.Video>>();
        initView();
        initViewModel();
        initData();
    }

    private List<Runnable> pauseRunnable = null;

    @Override
    protected void onResume() {
        super.onResume();
        if (pauseRunnable != null && pauseRunnable.size() > 0) {
            searchExecutorService = Executors.newFixedThreadPool(5);
            allRunCount.set(pauseRunnable.size());
            for (Runnable runnable : pauseRunnable) {
                searchExecutorService.execute(runnable);
            }
            pauseRunnable.clear();
            pauseRunnable = null;
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);
        llLayout = findViewById(R.id.llLayout);
        mSearchTitle = findViewById(R.id.mSearchTitle);
        mGridView = findViewById(R.id.mGridView);
        mGridViewWord = findViewById(R.id.mGridViewWord);
        mGridViewFilter = findViewById(R.id.mGridViewFilter);

        mGridViewWord.setHasFixedSize(true);
        mGridViewWord.setLayoutManager(new V7LinearLayoutManager(this.mContext, 1, false));
        spListAdapter = new FastListAdapter();
        mGridViewWord.setAdapter(spListAdapter);

//        mGridViewWord.setFocusable(true);
//        mGridViewWord.setOnFocusChangeListener(new View.OnFocusChangeListener() {
//            @Override
//            public void onFocusChange(View itemView, boolean hasFocus) {}
//        });

        mGridViewWord.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View child) {
                child.setFocusable(true);
                child.setOnFocusChangeListener(focusChangeListener);
                TextView t = (TextView) child;
                if (t.getText() == getString(R.string.fs_show_all)) {
                    t.requestFocus();
                }
//                if (child.isFocusable() && null == child.getOnFocusChangeListener()) {
//                    child.setOnFocusChangeListener(focusChangeListener);
//                }
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                view.setOnFocusChangeListener(null);
            }
        });

        spListAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                String spName = spListAdapter.getItem(position);
                filterResult(spName);
            }
        });
        // 添加选中放大效果
        mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        // mGridView.setHasFixedSize(true);
        mGridView.setLayoutManager(new V7GridLayoutManager(this.mContext, isBaseOnWidth() ? 4 : 5));

        searchAdapter = new FastSearchAdapter();
        mGridView.setAdapter(searchAdapter);

        searchAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapter.getData().get(position);
                if (video != null) {
                    try {
                        isSearchCancelled = true;
                        if (searchExecutorService != null) {
                            pauseRunnable = searchExecutorService.shutdownNow();
                            searchExecutorService = null;
                            JsLoader.stopAll();
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    jumpActivity(DetailActivity.class, bundle);
                }
            }
        });

        mGridViewFilter.setLayoutManager(new V7GridLayoutManager(this.mContext, isBaseOnWidth() ? 4 : 5));
        searchAdapterFilter = new FastSearchAdapter();
        mGridViewFilter.setAdapter(searchAdapterFilter);
        searchAdapterFilter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                FastClickCheckUtil.check(view);
                Movie.Video video = searchAdapterFilter.getData().get(position);
                if (video != null) {
                    try {
                        isSearchCancelled = true;
                        if (searchExecutorService != null) {
                            pauseRunnable = searchExecutorService.shutdownNow();
                            searchExecutorService = null;
                            JsLoader.stopAll();
                        }
                    } catch (Throwable th) {
                        th.printStackTrace();
                    }
                    Bundle bundle = new Bundle();
                    bundle.putString("id", video.id);
                    bundle.putString("sourceKey", video.sourceKey);
                    jumpActivity(DetailActivity.class, bundle);
                }
            }
        });

        setLoadSir(llLayout);

        // 分词
        searchWordAdapter = new SearchWordAdapter();
        mGridViewWordFenci = findViewById(R.id.mGridViewWordFenci);
        mGridViewWordFenci.setAdapter(searchWordAdapter);
        mGridViewWordFenci.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        searchWordAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                String str = searchWordAdapter.getData().get(position);
                search(str);
            }
        });
        searchWordAdapter.setNewData(new ArrayList<>());
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
    }

    private void filterResult(String spName) {
        if (spName == getString(R.string.fs_show_all)) {
            mGridView.setVisibility(View.VISIBLE);
            mGridViewFilter.setVisibility(View.GONE);
            return;
        }
        String key = spNames.get(spName);
        if (key.isEmpty()) return;

        if (searchFilterKey == key) return;
        searchFilterKey = key;

        List<Movie.Video> list = resultVods.get(key);
        searchAdapterFilter.setNewData(list);
        mGridView.setVisibility(View.GONE);
        mGridViewFilter.setVisibility(View.VISIBLE);
    }

    private void fenci() {
        if (!quickSearchWord.isEmpty()) return; // 如果经有分词了，不再进行二次分词
        // 分词
        OkGo.<String>get("https://api.yesapi.cn/?service=App.Scws.GetWords&text=" + searchTitle + "&app_key=CEE4B8A091578B252AC4C92FB4E893C3&sign=CB7602F3AC922808AF5D475D8DA33302")
                .tag("fenci")
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        } else {
                            throw new IllegalStateException("网络请求错误");
                        }
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        String json = response.body();
                        quickSearchWord.clear();
                        try {
                            JsonObject resJson = JsonParser.parseString(json).getAsJsonObject();
                            JsonElement wordsJson = resJson.get("data").getAsJsonObject().get("words");

                            for (JsonElement je : wordsJson.getAsJsonArray()) {
                                quickSearchWord.add(je.getAsJsonObject().get("word").getAsString());
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                        }
                        quickSearchWord.add(searchTitle);
                        EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_QUICK_SEARCH_WORD, quickSearchWord));
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                    }
                });
    }

    private void initCheckedSourcesForSearch() {
        mCheckSources = SearchHelper.getSourcesForSearch();
    }

    private void initData() {
        initCheckedSourcesForSearch();
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("title")) {
            String title = intent.getStringExtra("title");
            showLoading();
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_SEARCH) {
            String title = (String) event.obj;
            showLoading();
            search(title);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        // 检查 Activity 是否处于活跃状态
        if (isFinishing() || isDestroyed()) {
            return;
        }
        
        if (mSearchTitle != null) {
//            mSearchTitle.setText(String.format(getString(R.string.fs_results) + " : %d/%d", finishedCount, spNames.size()));
            finishedCount = searchAdapter.getData().size();
            mSearchTitle.setText(String.format(getString(R.string.fs_results) + " : %d", finishedCount));
        }
        if (event.type == RefreshEvent.TYPE_SEARCH_RESULT) {
            try {
                searchData(event.obj == null ? null : (AbsXml) event.obj);
            } catch (Exception e) {
                searchData(null);
            }
        } else if (event.type == RefreshEvent.TYPE_QUICK_SEARCH_WORD) {
            if (event.obj != null) {
                List<String> data = (List<String>) event.obj;
                searchWordAdapter.setNewData(data);
            }
        }
    }

    private void search(String title) {
        // 搜索开始时打印内存监控信息            
        Log.d("FastSearchActivity", "开始搜索：" + title);
        cancel();
        showLoading();
        this.searchTitle = title;
        fenci();
        mGridView.setVisibility(View.INVISIBLE);
        mGridViewFilter.setVisibility(View.GONE);
        searchAdapter.setNewData(new ArrayList<>());
        searchAdapterFilter.setNewData(new ArrayList<>());
    
        spListAdapter.reset();
        resultVods.clear();
        searchFilterKey = "";
        isFilterMode = false;
        spNames.clear();
        finishedCount = 0;
    
        searchResult();
    }

    private ExecutorService searchExecutorService = null;
    private final AtomicInteger allRunCount = new AtomicInteger(0);
    private static final int BATCH_SIZE = 50;
    private volatile boolean isSearchCancelled = false;

    private void searchResult() {
        isSearchCancelled = false;
        
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        } finally {
            searchAdapter.setNewData(new ArrayList<>());
            searchAdapterFilter.setNewData(new ArrayList<>());
            allRunCount.set(0);
        }
        
        List<SourceBean> searchRequestList = new ArrayList<>();
        searchRequestList.addAll(ApiConfig.get().getSourceBeanList());
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        searchRequestList.remove(home);
        searchRequestList.add(0, home);

        ArrayList<String> siteKey = new ArrayList<>();
        ArrayList<String> hots = new ArrayList<>();

        spListAdapter.setNewData(hots);
        spListAdapter.addData(getString(R.string.fs_show_all));
        for (SourceBean bean : searchRequestList) {
            if (!bean.isSearchable()) {
                continue;
            }
            if (mCheckSources != null && !mCheckSources.containsKey(bean.getKey())) {
                continue;
            }
            siteKey.add(bean.getKey());
            this.spNames.put(bean.getName(), bean.getKey());
            allRunCount.incrementAndGet();
        }

        if (!checkMemoryBeforeSearch()) {
            Toast.makeText(mContext, "内存不足，请清理后台应用后重试", Toast.LENGTH_LONG).show();
            return;
        }
        
        searchExecutorService = Executors.newFixedThreadPool(5);
        executeSearchBatches(siteKey, 0);
    }
    
    private boolean checkMemoryBeforeSearch() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMem = runtime.totalMemory() - runtime.freeMemory();
            long maxMem = runtime.maxMemory();
            double usagePercent = (double) usedMem / maxMem * 100;
            
            if (usagePercent > 85) {
                System.gc();
                SystemClock.sleep(200);
                
                usedMem = runtime.totalMemory() - runtime.freeMemory();
                usagePercent = (double) usedMem / maxMem * 100;
                
                if (usagePercent > 80) {
                    return false;
                }
            }
            return true;
        } catch (Throwable th) {
            return true;
        }
    }
    
    private void executeSearchBatches(final ArrayList<String> siteKey, final int batchStartIndex) {
        if (isSearchCancelled || batchStartIndex >= siteKey.size()) {
            finishSearch();
            return;
        }
        
        final int batchEndIndex = Math.min(batchStartIndex + BATCH_SIZE, siteKey.size());
        final List<String> batchKeys = siteKey.subList(batchStartIndex, batchEndIndex);
        
        final CountDownLatch batchLatch = new CountDownLatch(batchKeys.size());
        final AtomicInteger batchErrorCount = new AtomicInteger(0);
        
        for (final String key : batchKeys) {
            final String sourceKey = key;
            searchExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!isSearchCancelled) {
                            sourceViewModel.getSearch(sourceKey, searchTitle);
                        }
                    } catch (OutOfMemoryError e) {
                        batchErrorCount.incrementAndGet();
                        handleOOM(Thread.currentThread().getName(), sourceKey, e);
                    } catch (Exception e) {
                        batchErrorCount.incrementAndGet();
                        try {
                            EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                        } catch (Exception ex) {
                            Log.e("FastSearchActivity", "发布搜索结果事件异常", ex);
                        }
                    } catch (Throwable th) {
                        batchErrorCount.incrementAndGet();
                        if (th instanceof OutOfMemoryError) {
                            handleOOM(Thread.currentThread().getName(), sourceKey, (OutOfMemoryError) th);
                        } else {
                            try {
                                EventBus.getDefault().post(new RefreshEvent(RefreshEvent.TYPE_SEARCH_RESULT, null));
                            } catch (Exception ex) {
                                Log.e("FastSearchActivity", "发布搜索结果事件异常", ex);
                            }
                        }
                    } finally {
                        allRunCount.decrementAndGet();
                        batchLatch.countDown();
                    }
                }
            });
        }
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    batchLatch.await(5, TimeUnit.MINUTES);
                    
                    cleanupBetweenBatches();
                    
                    if (!isSearchCancelled) {
                        SystemClock.sleep(500);
                        executeSearchBatches(siteKey, batchEndIndex);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }
    
    private void finishSearch() {
        try {
            if (searchAdapter != null && searchAdapter.getData().size() <= 0) {
                try {
                    showEmpty();
                } catch (Exception e) {
                    Log.e("FastSearchActivity", "显示空结果异常", e);
                }
            }
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
            }
            JsLoader.stopAll();
            System.gc();
        } catch (Throwable th) {
            Log.e("FastSearchActivity", "完成搜索异常", th);
        }
    }
    
    private void cleanupBetweenBatches() {
        try {
            JsLoader.stopAll();
            JsLoader.destroyAllAndClear();
            System.gc();
            SystemClock.sleep(300);
            System.gc();
            SystemClock.sleep(200);
        } catch (Throwable th) {
            Log.e("FastSearchActivity", "批次间清理异常", th);
        }
    }
    
    private void handleOOM(String threadName, String sourceKey, OutOfMemoryError e) {
        try {
            if (searchExecutorService != null && !searchExecutorService.isShutdown()) {
                searchExecutorService.shutdownNow();
                JsLoader.stopAll();
            }
            
            // 尝试释放更多资源
            System.gc();
            System.gc();
            SystemClock.sleep(500);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // 向过滤栏添加有结果的spname
    private String addWordAdapterIfNeed(String key) {
        try {
            String name = "";
            for (String n : spNames.keySet()) {
                if (spNames.get(n) == key) {
                    name = n;
                }
            }
            if (name == "") return key;

            List<String> names = spListAdapter.getData();
            for (int i = 0; i < names.size(); ++i) {
                if (name == names.get(i)) {
                    return key;
                }
            }

            spListAdapter.addData(name);
            return key;
        } catch (Exception e) {
            return key;
        }
    }

    private void searchData(AbsXml absXml) {
        try {
            String lastSourceKey = "";

            if (absXml != null && absXml.movie != null && absXml.movie.videoList != null && absXml.movie.videoList.size() > 0) {
                try {
                    List<Movie.Video> data = new ArrayList<>();
                    for (Movie.Video video : absXml.movie.videoList) {
                        try {
                            if (video != null) {
                                data.add(video);
                                try {
                                    if (resultVods != null && video.sourceKey != null) {
                                        if (!resultVods.containsKey(video.sourceKey)) {
                                            resultVods.put(video.sourceKey, new ArrayList<Movie.Video>());
                                        }
                                        resultVods.get(video.sourceKey).add(video);
                                        if (video.sourceKey != lastSourceKey) {
                                            try {
                                                lastSourceKey = this.addWordAdapterIfNeed(video.sourceKey);
                                            } catch (Exception e) {
                                                Log.e("FastSearchActivity", "添加适配器数据异常", e);
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.e("FastSearchActivity", "处理搜索结果异常", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.e("FastSearchActivity", "处理视频项异常", e);
                        }
                    }

                    if (!data.isEmpty() && searchAdapter != null) {
                        try {
                            if (searchAdapter.getData().size() > 0) {
                                searchAdapter.addData(data);
                            } else {
                                try {
                                    showSuccess();
                                } catch (Exception e) {
                                    Log.e("FastSearchActivity", "显示成功状态异常", e);
                                }
                                if (!isFilterMode && mGridView != null) {
                                    mGridView.setVisibility(View.VISIBLE);
                                }
                                searchAdapter.setNewData(data);
                            }
                        } catch (Exception e) {
                            Log.e("FastSearchActivity", "更新适配器数据异常", e);
                        }
                    }
                } catch (Exception e) {
                    Log.e("FastSearchActivity", "处理搜索结果异常", e);
                }
            }

            try {
                int count = allRunCount.get();
                if (count <= 0) {
                    try {
                        if (searchAdapter != null && searchAdapter.getData().size() <= 0) {
                            try {
                                showEmpty();
                            } catch (Exception e) {
                                Log.e("FastSearchActivity", "显示空结果异常", e);
                            }
                        }
                        try {
                            cancel();
                        } catch (Exception e) {
                            Log.e("FastSearchActivity", "取消搜索异常", e);
                        }
                    } catch (Exception e) {
                        Log.e("FastSearchActivity", "处理搜索完成异常", e);
                    }
                }
            } catch (Exception e) {
                Log.e("FastSearchActivity", "获取运行计数异常", e);
            }
        } catch (Exception e) {
            Log.e("FastSearchActivity", "搜索数据处理异常", e);
        }
    }

    private void cancel() {
        OkGo.getInstance().cancelTag("search");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isSearchCancelled = true;
        cancel();
        try {
            if (searchExecutorService != null) {
                searchExecutorService.shutdownNow();
                searchExecutorService = null;
                JsLoader.stopAll();
            }
        } catch (Throwable th) {
            Log.e("FastSearchActivity", "销毁活动异常", th);
        }
        EventBus.getDefault().unregister(this);
    }
}
