package com.pctutu.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Random;

/** Full-screen TV interface. App-owned screens are painted here to avoid platform dialogs. */
public final class HomeSurface extends View {
    public interface Actions {
        void setDefaultLauncher();
        void openSystemFeature(String feature);
        void cleanMemory();
        boolean ensureStorageAccess();
        boolean isDeviceOwner();
        void startLockMode();
        void setAutoStart(boolean enabled);
        void setAppLanguage(String mode);
        boolean isAutoStartEnabled();
        void requestWeather(String city);
        String getWeatherCity();
        boolean isFahrenheit();
        void setFahrenheit(boolean value);
        File getStorageRoot();
        void openFile(File file);
        void installApk(File file);
    }

    private static final int HOME=0, APPS=1, SETTINGS=2, WEATHER=3, CITY_PICKER=4, SAVER=5, WALLPAPER=6, FILES=7, FILE_MENU=8, RENAME=9, DELETE=10, PREVIEW=11, ABOUT=12, LANGUAGE=13, ICON_COUNT=14;
    private static final int W=1280, H=720, WHITE=Color.WHITE, CYAN=0xff10c3ae;
    private static final int[] WALLPAPERS={R.drawable.ocean_sky,R.drawable.lake_reflection,R.drawable.snow_mountain_sky,R.drawable.autumn_valley,R.drawable.waterfall_coast,R.drawable.clear_sky,R.drawable.forest_morning,R.drawable.grassland_sky,R.drawable.night_sky};
    private static final String[] WALL_NAMES={"海洋与海岸","湖泊","雪山","山峰","山谷瀑布","晴空","森林","草原","夜空"};
    private static final String[] CITIES={"北京","上海","广州","深圳","成都","重庆","武汉","西安","杭州","南京","天津","青岛","沈阳","哈尔滨","昆明","厦门","香港","澳门"};
    private static final String[] SETTING_NAMES={"重新激活为默认桌面","开机自启动","天气设置","屏保设置","壁纸选择","文件管理","APK 安装器","语言","首页图标数量","关于"};
    private static final int[] SETTING_COLORS={0xff19b998,0xff438bec,0xffffc631,0xff00c5ac,0xff174a63,0xff4288e9,0xff078676,0xff5686d8,0xffd17a43,0xff078676};
    private static final String[] TOP_FEATURES={"网络连接","内存清理","信号来源","存储设备","桌面设置"};

    private final Actions actions;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path path=new Path();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ArrayList<ResolveInfo> apps=new ArrayList<ResolveInfo>();
    private final HashMap<String,Drawable> iconCache=new HashMap<String,Drawable>();
    private final HashMap<String,String> labelCache=new HashMap<String,String>();
    private final ResolveInfo[] slots=new ResolveInfo[10];
    private final ArrayList<File> files=new ArrayList<File>();
    private final int[] starX=new int[90],starY=new int[90];
    private final Bitmap[] thumbnailCache=new Bitmap[WALLPAPERS.length];
    private final Random random=new Random(12);
    private Bitmap background;
    private int state=HOME, previousState=HOME, selected=0, topSelection=4, page=0, slotTarget=-1, menuSelected=0, category=2, fileScroll=0, fileSelection=0, saverMode;
    private int wallpaperId=R.drawable.snow_mountain_sky, temperatureC=12, humidity=60;
    private boolean slotMenu, topFeaturesVisible, autoWeather=true, fahrenheit, autoStart, saverActive, deleteApk;
    private String languageMode="system";
    private int homeIconCount=5;
    private String city="北京",cityInput="北京",condition="阴，多云",notice="",renameText="",fileError="";
    private File currentDir, selectedFile;
    private float scaleX=1f,scaleY=1f,downX,downY;
    private long downTime;
    private int lastFocus=0;
    private final Runnable idleRunnable=new Runnable(){@Override public void run(){if(state==HOME&&saverMode<3)showSaver(false);}};
    private final Runnable clockRunnable=new Runnable(){@Override public void run(){if(state==HOME||state==PREVIEW)invalidate();handler.postDelayed(this,1000);}};

    HomeSurface(Context context,Actions actions) {
        super(context); this.actions=actions; setFocusable(true); setFocusableInTouchMode(true); requestFocus();
        SharedPreferences prefs=context.getSharedPreferences("home_slots",0);
        for(int i=0;i<10;i++) slots[i]=null;
        SharedPreferences p=context.getSharedPreferences("surface_state",0);
        wallpaperId=p.getInt("wallpaper",R.drawable.snow_mountain_sky); saverMode=context.getSharedPreferences("settings",0).getInt("saver",3); homeIconCount=p.getInt("home_icon_count",5)==10?10:5;
        city=actions.getWeatherCity(); fahrenheit=actions.isFahrenheit(); autoStart=actions.isAutoStartEnabled(); autoWeather=p.getBoolean("auto_weather",true); languageMode=p.getString("language","system");
        if(wallpaperId==0)wallpaperId=R.drawable.snow_mountain_sky;
        for(int i=0;i<starX.length;i++){starX[i]=random.nextInt(W);starY[i]=random.nextInt(H);}
        loadApps(); restoreSlots(prefs); loadBackgroundAsync(); loadFiles(actions.getStorageRoot()); resetIdle(); handler.post(clockRunnable);
    }

    @Override protected void onDetachedFromWindow(){
        handler.removeCallbacks(clockRunnable);
        handler.removeCallbacks(idleRunnable);
        super.onDetachedFromWindow();
    }

    private void loadApps(){
        HashMap<String,ResolveInfo> unique=new HashMap<String,ResolveInfo>(); PackageManager pm=getContext().getPackageManager();
        Intent lean=new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER");
        Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try{for(ResolveInfo r:pm.queryIntentActivities(lean,0))addApp(unique,r);for(ResolveInfo r:pm.queryIntentActivities(launch,0))addApp(unique,r);}catch(Exception ignored){}
        apps.clear();apps.addAll(unique.values());Collections.sort(apps,(a,b)->a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
    }
    private void addApp(HashMap<String,ResolveInfo> all,ResolveInfo info){if(info==null||info.activityInfo==null||getContext().getPackageName().equals(info.activityInfo.packageName))return;all.put(info.activityInfo.packageName+"/"+info.activityInfo.name,info);}
    private void restoreSlots(SharedPreferences p){for(int i=0;i<10;i++){String key=p.getString("slot_"+i+"_component","");for(ResolveInfo r:apps)if(component(r).equals(key)){slots[i]=r;break;}}}
    private String component(ResolveInfo r){return r.activityInfo.packageName+"/"+r.activityInfo.name;}
    private void saveSlot(int index,ResolveInfo app){slots[index]=app;getContext().getSharedPreferences("home_slots",0).edit().putString("slot_"+index+"_component",component(app)).apply();}
    private void clearSlot(int index){slots[index]=null;getContext().getSharedPreferences("home_slots",0).edit().remove("slot_"+index+"_component").apply();}
    private void loadBackground(){BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;o.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap b=BitmapFactory.decodeResource(getResources(),wallpaperId,o);if(b==null)b=BitmapFactory.decodeResource(getResources(),R.drawable.snow_mountain_sky,o);background=b;}
    private void loadBackgroundAsync(){final int requested=wallpaperId;new Thread(new Runnable(){@Override public void run(){BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=2;o.inPreferredConfig=Bitmap.Config.RGB_565;Bitmap b=BitmapFactory.decodeResource(getResources(),requested,o);if(b==null)b=BitmapFactory.decodeResource(getResources(),R.drawable.snow_mountain_sky,o);final Bitmap result=b;handler.post(new Runnable(){@Override public void run(){if(result!=null&&requested==wallpaperId){background=result;invalidate();}}});}}).start();}
    private void loadFiles(File dir){currentDir=dir;files.clear();fileError="";if(dir==null||!dir.exists()||!dir.canRead()){fileError="无法读取此位置";return;}File[] list=dir.listFiles();if(list==null){fileError="此位置不可访问";return;}for(File f:list){if(!isApkMode()||f.isDirectory()||f.getName().toLowerCase(Locale.US).endsWith(".apk"))files.add(f);}Collections.sort(files,(a,b)->{if(a.isDirectory()!=b.isDirectory())return a.isDirectory()?-1:1;return a.getName().compareToIgnoreCase(b.getName());});fileScroll=0;fileSelection=0;}
    private boolean isApkMode(){return Boolean.TRUE.equals(getTag());}
    private void loadFilesMode(File dir,boolean apk){setTag(Boolean.valueOf(apk));loadFiles(dir);}

    public void setWeather(WeatherProvider.WeatherData data,boolean fahrenheit){city=data.city;temperatureC=data.temperatureC;humidity=data.humidity;condition=conditionName(data.condition);this.fahrenheit=fahrenheit;invalidate();}
    public void setWeatherError(){condition="暂时无法获取天气";invalidate();}
    public void setNotice(String text){notice=text==null?"":text;invalidate();handler.postDelayed(()->{notice="";invalidate();},2600);}
    public void resetIdle(){handler.removeCallbacks(idleRunnable);if(state==HOME&&saverMode<3)handler.postDelayed(idleRunnable,60000);}
    public void back(){if(state==HOME){slotMenu=false;topFeaturesVisible=false;lastFocus=0;invalidate();return;}if(state==PREVIEW){state=HOME;topFeaturesVisible=false;lastFocus=0;resetIdle();invalidate();return;}if(state==CITY_PICKER){state=WEATHER;selected=2;}else if(state==FILE_MENU||state==RENAME||state==DELETE)state=FILES;else if(state==FILES&&currentDir!=null&&currentDir.getParentFile()!=null){loadFilesMode(currentDir.getParentFile(),isApkMode());}else if(state==SETTINGS||state==APPS){state=HOME;slotTarget=-1;topFeaturesVisible=false;lastFocus=0;}else if(state==WEATHER||state==SAVER||state==WALLPAPER||state==FILES||state==ABOUT||state==LANGUAGE||state==ICON_COUNT)state=SETTINGS;else state=HOME;selected=0;invalidate();}
    public boolean handleKey(KeyEvent event){int k=event.getKeyCode();if(event.getAction()!=KeyEvent.ACTION_DOWN)return true;resetIdle();boolean ok=k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_NUMPAD_ENTER||k==KeyEvent.KEYCODE_BUTTON_A;
        if(state==PREVIEW){if(k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_MENU||ok){back();return true;}return true;}
        if(k==KeyEvent.KEYCODE_BACK){back();return true;}
        if(state==HOME){if(k==KeyEvent.KEYCODE_MENU||k==KeyEvent.KEYCODE_BUTTON_B){if(slotMenu){slotMenu=false;}else if(topFeaturesVisible&&lastFocus==1){openTopFeature(topSelection);}else{slotMenu=true;menuSelected=0;}invalidate();return true;}if(slotMenu){if(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN){menuSelected=1-menuSelected;invalidate();return true;}if(ok){if(menuSelected==0){slotTarget=selected;state=APPS;page=0;selected=0;slotMenu=false;}else if(menuSelected==1){clearSlot(selected);slotMenu=false;}else slotMenu=false;invalidate();return true;}return true;}if(k==KeyEvent.KEYCODE_DPAD_UP){if(homeIconCount==10&&selected>=5)selected-=5;else{topFeaturesVisible=true;lastFocus=1;}invalidate();return true;}if(k==KeyEvent.KEYCODE_DPAD_DOWN){if(topFeaturesVisible&&lastFocus==1){topFeaturesVisible=false;lastFocus=0;selected=0;}else if(homeIconCount==10&&selected<5){selected+=5;}else{state=APPS;page=0;selected=0;}invalidate();return true;}if(k==KeyEvent.KEYCODE_DPAD_LEFT){if(topFeaturesVisible&&lastFocus==1)topSelection=Math.max(0,topSelection-1);else selected=(selected%5==0)?selected:selected-1;invalidate();return true;}if(k==KeyEvent.KEYCODE_DPAD_RIGHT){if(topFeaturesVisible&&lastFocus==1)topSelection=Math.min(4,topSelection+1);else selected=Math.min(homeIconCount-1,selected+1);invalidate();return true;}if(ok){if(topFeaturesVisible&&lastFocus==1)openTopFeature(topSelection);else if(slots[selected]!=null)launch(slots[selected]);else{slotTarget=selected;state=APPS;page=0;selected=0;}invalidate();return true;}return true;}
        if(state==APPS){int maxPage=Math.max(0,(apps.size()-1)/20);if(k==KeyEvent.KEYCODE_MENU){back();return true;}if(k==KeyEvent.KEYCODE_DPAD_LEFT){if(selected%5>0)selected--;else if(page>0){page--;selected=19;}invalidate();return true;}if(k==KeyEvent.KEYCODE_DPAD_RIGHT){if(selected%5<4&&page*20+selected+1<apps.size())selected++;else if(page<maxPage){page++;selected=0;}invalidate();return true;}if(k==KeyEvent.KEYCODE_DPAD_UP){if(selected>=5)selected-=5;else if(page>0){page--;selected+=15;}else back();invalidate();return true;}if(k==KeyEvent.KEYCODE_DPAD_DOWN){if(selected<15){if(page*20+selected+5<apps.size())selected+=5;}else if(page<maxPage){page++;selected-=15;}invalidate();return true;}if(ok){int ix=page*20+selected;if(ix<apps.size()){ResolveInfo r=apps.get(ix);if(slotTarget>=0){saveSlot(slotTarget,r);slotTarget=-1;state=HOME;lastFocus=0;}else launch(r);}invalidate();return true;}
            return true;}
        if(state==SETTINGS){if(k==KeyEvent.KEYCODE_DPAD_LEFT){if(selected%3>0)selected--;}else if(k==KeyEvent.KEYCODE_DPAD_RIGHT){if(selected<SETTING_NAMES.length-1&&selected%3<2)selected++;}else if(k==KeyEvent.KEYCODE_DPAD_UP)selected=Math.max(0,selected-3);else if(k==KeyEvent.KEYCODE_DPAD_DOWN)selected=Math.min(SETTING_NAMES.length-1,selected+3);else if(ok)openSetting(selected);invalidate();return true;}
        if(state==LANGUAGE){if(k==KeyEvent.KEYCODE_DPAD_UP)selected=Math.max(0,selected-1);else if(k==KeyEvent.KEYCODE_DPAD_DOWN)selected=Math.min(2,selected+1);else if(ok){languageMode=selected==0?"system":selected==1?"zh":"en";getContext().getSharedPreferences("surface_state",0).edit().putString("language",languageMode).apply();actions.setAppLanguage(languageMode);state=SETTINGS;selected=7;}invalidate();return true;}
        if(state==ICON_COUNT){if(k==KeyEvent.KEYCODE_DPAD_UP)selected=0;else if(k==KeyEvent.KEYCODE_DPAD_DOWN)selected=1;else if(ok){homeIconCount=selected==0?5:10;getContext().getSharedPreferences("surface_state",0).edit().putInt("home_icon_count",homeIconCount).apply();state=SETTINGS;selected=10;}invalidate();return true;}
        if(state==WEATHER){if(k==KeyEvent.KEYCODE_DPAD_UP)selected=Math.max(0,selected-1);else if(k==KeyEvent.KEYCODE_DPAD_DOWN)selected=Math.min(2,selected+1);else if(ok){if(selected==0){autoWeather=!autoWeather;getContext().getSharedPreferences("surface_state",0).edit().putBoolean("auto_weather",autoWeather).apply();}else if(selected==1){state=CITY_PICKER;cityInput=city;selected=cityIndex();}else{fahrenheit=!fahrenheit;actions.setFahrenheit(fahrenheit);} }invalidate();return true;}
        if(state==CITY_PICKER){int unicode=event.getUnicodeChar();if(k==KeyEvent.KEYCODE_DEL){if(cityInput.length()>0)cityInput=cityInput.substring(0,cityInput.length()-1);}else if(ok){String value=cityInput.trim();if(value.length()>0){city=value;getContext().getSharedPreferences("surface_state",0).edit().putString("weather_city",value).apply();actions.requestWeather(value);state=WEATHER;selected=1;}}else if(unicode>0&&!Character.isISOControl(unicode)&&unicode<65536){cityInput+=Character.toString((char)unicode);}else if(k==KeyEvent.KEYCODE_DPAD_UP){selected=Math.max(0,selected-4);cityInput=CITIES[selected];}else if(k==KeyEvent.KEYCODE_DPAD_DOWN){selected=Math.min(CITIES.length-1,selected+4);cityInput=CITIES[selected];}else if(k==KeyEvent.KEYCODE_DPAD_LEFT){selected=selected%4==0?selected:selected-1;cityInput=CITIES[selected];}else if(k==KeyEvent.KEYCODE_DPAD_RIGHT){selected=Math.min(CITIES.length-1,selected+1);cityInput=CITIES[selected];}invalidate();return true;}
        if(state==SAVER){if(k==KeyEvent.KEYCODE_DPAD_UP)selected=Math.max(0,selected-1);else if(k==KeyEvent.KEYCODE_DPAD_DOWN)selected=Math.min(3,selected+1);else if(ok){if(selected<3){saverMode=selected;getContext().getSharedPreferences("settings",0).edit().putInt("saver",saverMode).apply();showSaver(true);}else{getContext().getSharedPreferences("settings",0).edit().putInt("saver",3).apply();saverMode=3;setNotice("屏保已关闭");}}invalidate();return true;}
        if(state==WALLPAPER){if(k==KeyEvent.KEYCODE_DPAD_LEFT){if(selected%3>0)selected--;}else if(k==KeyEvent.KEYCODE_DPAD_RIGHT){if(selected<wallpaperIndexes().size()-1)selected++;}else if(k==KeyEvent.KEYCODE_DPAD_UP){if(selected>=3)selected-=3;}else if(k==KeyEvent.KEYCODE_DPAD_DOWN){if(selected+3<wallpaperIndexes().size())selected+=3;}else if(ok)chooseWallpaper();invalidate();return true;}
        if(state==FILES){if(k==KeyEvent.KEYCODE_DPAD_UP)fileSelection=Math.max(0,fileSelection-1);else if(k==KeyEvent.KEYCODE_DPAD_DOWN)fileSelection=Math.min(files.size()-1,fileSelection+1);else if(k==KeyEvent.KEYCODE_DPAD_LEFT)fileSelection=Math.max(0,fileSelection-8);else if(k==KeyEvent.KEYCODE_DPAD_RIGHT)fileSelection=Math.min(files.size()-1,fileSelection+8);else if(k==KeyEvent.KEYCODE_MENU){if(fileSelection<files.size()){selectedFile=files.get(fileSelection);state=FILE_MENU;selected=0;}}else if(ok)activateFile();adjustFileScroll();invalidate();return true;}
        if(state==FILE_MENU){if(k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN)selected=1-selected;else if(ok){if(selected==0){renameText=selectedFile.getName();state=RENAME;selected=0;}else state=DELETE;}invalidate();return true;}
        if(state==DELETE){if(k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT)selected=1-selected;else if(ok){if(selected==0){boolean success=removeTree(selectedFile);setNotice(success?"已删除":"删除失败");loadFiles(currentDir);}state=FILES;}invalidate();return true;}
        if(state==RENAME){handleRenameKey(k,event,ok);invalidate();return true;}
        return true;
    }

    private void openTopFeature(int index){topFeaturesVisible=false;lastFocus=0;if(index==0)actions.openSystemFeature("network");else if(index==1)actions.cleanMemory();else if(index==2)actions.openSystemFeature("source");else if(index==3)actions.openSystemFeature("storage");else openSettings();}
    private void openSetting(int index){selected=index;switch(index){case 0:actions.setDefaultLauncher();break;case 1:autoStart=!autoStart;actions.setAutoStart(autoStart);break;case 2:state=WEATHER;selected=0;break;case 3:state=SAVER;selected=saverMode<3?saverMode:0;break;case 4:state=WALLPAPER;selected=0;break;case 5:case 6:if(actions.ensureStorageAccess()){state=FILES;loadFilesMode(actions.getStorageRoot(),index==6);}else setNotice("授予文件访问权限后再次打开");break;case 7:state=LANGUAGE;selected="en".equals(languageMode)?2:"zh".equals(languageMode)?1:0;break;case 8:state=ICON_COUNT;selected=homeIconCount==10?1:0;break;case 9:state=ABOUT;break;}invalidate();}
    private void launch(ResolveInfo r){try{Intent i=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(r.activityInfo.packageName,r.activityInfo.name);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getContext().startActivity(i);}catch(Exception e){setNotice("无法启动此应用");}}
    private int cityIndex(){for(int i=0;i<CITIES.length;i++)if(CITIES[i].equals(city))return i;return 0;}
    private void showSaver(boolean preview){state=PREVIEW;saverActive=true;invalidate();}
    private void activateFile(){if(fileSelection<0||fileSelection>=files.size())return;File f=files.get(fileSelection);if(f.isDirectory()){loadFilesMode(f,isApkMode());}else if(isApkMode()||f.getName().toLowerCase(Locale.US).endsWith(".apk"))actions.installApk(f);else actions.openFile(f);}
    private void adjustFileScroll(){if(fileSelection<fileScroll)fileScroll=fileSelection;if(fileSelection>=fileScroll+9)fileScroll=fileSelection-8;}
    private boolean removeTree(File f){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File child:children)if(!removeTree(child))return false;}return f.delete();}
    private void handleRenameKey(int key,KeyEvent event,boolean ok){
        String[] rows={"1234567890","QWERTYUIOP","ASDFGHJKL","ZXCVBNM"};int row=selected/10,col=selected%10;
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){selected=Math.max(0,selected-1);return;}if(key==KeyEvent.KEYCODE_DPAD_RIGHT){selected=Math.min(42,selected+1);return;}if(key==KeyEvent.KEYCODE_DPAD_UP){selected=Math.max(0,selected-10);return;}if(key==KeyEvent.KEYCODE_DPAD_DOWN){selected=Math.min(42,selected+10);return;}
        if(key==KeyEvent.KEYCODE_DEL){if(renameText.length()>0)renameText=renameText.substring(0,renameText.length()-1);return;}
        if(ok){if(selected<40){row=selected/10;col=selected%10;if(row<rows.length&&col<rows[row].length())renameText+=rows[row].charAt(col);}else if(selected==40)renameText+=" ";else if(selected==41){if(renameText.length()>0)renameText=renameText.substring(0,renameText.length()-1);}else{String name=renameText.trim();if(name.length()>0){File target=new File(selectedFile.getParentFile(),name);setNotice(selectedFile.renameTo(target)?"重命名成功":"重命名失败");loadFiles(currentDir);}state=FILES;selected=0;}return;}
        int code=event.getUnicodeChar();if(code>0&&code<128&&!Character.isISOControl(code))renameText+=(char)code;
    }
    private void chooseWallpaper(){ArrayList<Integer> list=wallpaperIndexes();if(selected<0||selected>=list.size())return;wallpaperId=WALLPAPERS[list.get(selected)];getContext().getSharedPreferences("surface_state",0).edit().putInt("wallpaper",wallpaperId).apply();loadBackgroundAsync();state=HOME;setNotice("壁纸已应用");}
    private ArrayList<Integer> wallpaperIndexes(){ArrayList<Integer> out=new ArrayList<Integer>();for(int i=0;i<WALLPAPERS.length;i++)out.add(i);return out;}
    private String conditionName(String provider){if(provider==null||provider.length()==0||provider.equals("MET Norway"))return "天气已更新";return provider;}

    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);scaleX=getWidth()/(float)W;scaleY=getHeight()/(float)H;canvas.save();canvas.scale(scaleX,scaleY);switch(state){case HOME:drawHome(canvas);break;case APPS:drawApps(canvas);break;case SETTINGS:drawSettings(canvas);break;case WEATHER:drawWeather(canvas);break;case CITY_PICKER:drawCities(canvas);break;case SAVER:drawSaverSettings(canvas);break;case WALLPAPER:drawWallpaper(canvas);break;case FILES:drawFiles(canvas);break;case FILE_MENU:drawFileMenu(canvas);break;case RENAME:drawRename(canvas);break;case DELETE:drawDelete(canvas);break;case PREVIEW:drawPreview(canvas);break;case ABOUT:drawAbout(canvas);break;case LANGUAGE:drawLanguage(canvas);break;case ICON_COUNT:drawIconCount(canvas);break;}if(notice.length()>0)drawNotice(canvas);canvas.restore();}
    private void fill(Canvas c,int color,float l,float t,float r,float b){paint.setColor(color);paint.setStyle(Paint.Style.FILL);c.drawRect(l,t,r,b,paint);}
    private void round(Canvas c,int color,float l,float t,float r,float b,float radius){paint.setColor(color);paint.setStyle(Paint.Style.FILL);RectF rect=new RectF(l,t,r,b);path.reset();path.addRoundRect(rect,radius,radius,Path.Direction.CW);c.drawPath(path,paint);}
    private void strokeRound(Canvas c,int color,float l,float t,float r,float b,float radius,float width){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);path.reset();path.addRoundRect(new RectF(l,t,r,b),radius,radius,Path.Direction.CW);c.drawPath(path,paint);paint.setStyle(Paint.Style.FILL);}
    private boolean isEnglish(){return "en".equals(languageMode)||("system".equals(languageMode)&&!Locale.getDefault().getLanguage().toLowerCase(Locale.US).startsWith("zh"));}
    private String cityLabel(String value){if(!isEnglish())return value;String[] zh={"北京","上海","广州","深圳","成都","重庆","武汉","西安","杭州","南京","天津","青岛","沈阳","哈尔滨","昆明","厦门","香港","澳门"};String[] en={"Beijing","Shanghai","Guangzhou","Shenzhen","Chengdu","Chongqing","Wuhan","Xi'an","Hangzhou","Nanjing","Tianjin","Qingdao","Shenyang","Harbin","Kunming","Xiamen","Hong Kong","Macau"};for(int i=0;i<zh.length;i++)if(zh[i].equals(value))return en[i];return value;}
    private String ui(String value){
        if(value==null||!isEnglish())return value==null?"":value;
        String[][] pairs={
            {"默认桌面","Default launcher"},{"重新激活为默认桌面","Re-activate as default launcher"},{"开机自启动","Start on boot"},{"天气设置","Weather"},{"屏保设置","Screensaver"},{"壁纸选择","Wallpaper"},{"文件管理","File manager"},{"APK 安装器","APK installer"},{"关于","About"},{"语言","Language"},
            {"首页图标数量","Home icon count"},{"感谢：MET Norway 天气数据服务","Thanks: MET Norway weather service"},{"超级桌面","SuperHome"},{"开机自动恢复超级Home","Launch SuperHome at boot"},{"授予文件访问权限后再次打开","Grant file access, then open again"},{"感谢：MET Norway 天气数据服务","Thanks: MET Norway weather service"},{"5 个","5"},{"10 个","10"},{"显示一行五个应用槽位","Show one row with five apps"},{"显示两行共十个应用槽位","Show two rows with ten apps"},
            {"网络连接","Network"},{"内存清理","Memory cleanup"},{"信号来源","Input source"},{"存储设备","Storage"},{"桌面设置","Home settings"},{"常用应用","Favorite apps"},{"添加应用","Add app"},{"替换应用","Replace app"},{"清空槽位","Clear slot"},{"取消","Cancel"},
            {"主屏幕与设备启动","Home screen and startup"},{"开机自动恢复超级桌面","Launch SuperHome at boot"},{"MET Norway 天气服务","Forecasts by MET Norway"},{"闲置时自动显示","Shown while idle"},{"自然风景照片","Nature photographs"},{"浏览和管理文件","Browse and manage files"},{"本地 APK 安装","Install APK files"},{"版本与开源信息","Version and open-source notices"},{"设备所有者模式","Device owner mode"},{"已开启","On"},{"已关闭","Off"},{"已授权","Authorized"},{"需设备所有者","Device owner required"},
            {"跟随系统","System default"},{"中文","Chinese"},{"英文","English"},{"已应用","Applied"},{"应用语言","App language"},{"选择界面显示语言","Choose the display language"},
            {"自动获取天气位置","Use automatic weather location"},{"手动设置天气位置","Choose weather location"},{"摄氏度 / 华氏度切换","Temperature units"},{"天气来源：MET Norway Locationforecast","Weather data: MET Norway Locationforecast"},{"按 MENU 返回设置","MENU: back to settings"},{"选择城市","Choose city"},{"当前：","Current: "},{"选择城市后将更新天气","Weather updates after selection"},{"开","On"},{"关","Off"},{"摄氏","Celsius"},{"华氏","Fahrenheit"},
            {"‹  华氏  ›","‹  Fahrenheit  ›"},{"‹  摄氏  ›","‹  Celsius  ›"},{"使用电视系统语言","Use TV system language"},{"简体中文界面","Simplified Chinese interface"},{"选择后立即应用并保存","Select to apply and save"},{"10 个","10"},{"显示两行共十个应用槽位","Show two rows with ten app slots"},{"MENU 返回桌面","MENU: return home"},
            {"阴，多云","Cloudy, partly cloudy"},{"天气已更新","Weather updated"},{"晴","Clear"},{"晴间多云","Fair, partly cloudy"},{"多云","Cloudy"},{"有雨","Rain"},{"小雨","Light rain"},{"大雨","Heavy rain"},{"降雪","Snow"},{"大雪","Heavy snow"},{"雾","Fog"},{"雷雨","Thunderstorms"},{"雨夹雪","Sleet"},{"首页显示 5 或 10 个应用","Home shows 5 or 10 apps"},{"选择全球城市","Choose a global city"},{"输入英文或中文城市名，按 OK 查询全球天气","Enter a city in English or Chinese, then press OK"},{"输入英文或中文城市名，按 OK 查询全球天气","Enter a city in English or Chinese, then press OK"},{"DEL 删除   OK 查询   返回取消","DEL delete   OK search   Back cancel"},
            {"星空漫游","Starfield"},{"数字时钟","Digital clock"},{"风景照片轮播","Scenic slideshow"},{"关闭屏保","Disable screensaver"},{"动态星空与柔和光点","Animated stars and soft lights"},{"全屏时钟与日期","Full-screen clock and date"},{"使用内置自然风景照片","Built-in nature photos"},{"屏幕闲置时保持桌面","Keep the home screen while idle"},{"选择效果即可立即预览，返回后自动保存","Select an effect to preview; it saves automatically"},{"按返回键结束预览","Press Back to end preview"},
            {"选择图片后应用为桌面壁纸","Select a photo to apply as wallpaper"},{"海洋与海岸","Ocean and coast"},{"湖泊","Lake"},{"雪山","Snow mountains"},{"山峰","Mountain peaks"},{"森林","Forest"},{"晴空","Clear sky"},{"草原","Grassland"},{"夜空","Night sky"},{"山谷瀑布","Valley waterfall"},
            {"MET Norway 天气 · 自然风景屏保","MET Norway weather and nature screensaver"},{"版本 1.0.0","Version 1.0.0"},{"超级Home","SuperHome"},
            {"APK 安装器","APK installer"},{"此位置不可访问","Location is not accessible"},{"无法读取此位置","Cannot read this location"},{"此文件夹为空","This folder is empty"},{"文件夹","Folder"},{"文件操作","File actions"},{"重命名","Rename"},{"删除","Delete"},{"确认删除此文件？","Delete this file?"},{"删除失败","Delete failed"},{"已删除","Deleted"},{"重命名成功","Renamed successfully"},{"重命名失败","Rename failed"},{"空格","Space"},{"删除字符","Delete character"},{"保存","Save"},{"MENU 取消","MENU: cancel"},{"取消：返回","Cancel: back"},{"↑↓选择   OK 打开   MENU 操作   返回上级","↑↓ Select   OK Open   MENU Actions   Back: parent folder"},
            {"没有可用的第三方应用","No launchable apps found"},{"无法启动此应用","Unable to launch this app"},{"天气已更新","Weather updated"},{"暂时无法获取天气","Weather is temporarily unavailable"},{"壁纸已应用","Wallpaper applied"},{"屏保已关闭","Screensaver disabled"},{"MET Norway 天气","MET Norway weather"}
        };
        for(String[] pair:pairs)if(pair[0].equals(value))return pair[1];
        if(value.startsWith("天气  "))return "Weather  "+value.substring(4);
        if(value.startsWith("已向 ")&&value.endsWith(" 个应用发送后台清理请求"))return value.substring(3,value.length()-13)+" apps received a background cleanup request";
        if(value.startsWith("当前："))return "Current: "+cityLabel(value.substring(3));
        return cityLabel(value);
    }
    private void text(Canvas c,String s,float x,float baseline,int size,int color){s=ui(s);paint.setColor(color);paint.setStyle(Paint.Style.FILL);paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));paint.setTextSize(size);c.drawText(s,x,baseline,paint);}
    private void center(Canvas c,String s,float cx,float baseline,int size,int color,float maxWidth){s=ui(s);paint.setTextSize(size);while(s.length()>1&&paint.measureText(s)>maxWidth)s=s.substring(0,s.length()-1);paint.setColor(color);paint.setTypeface(android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL));c.drawText(s,cx-paint.measureText(s)/2,baseline,paint);}
    private void drawBackground(Canvas c){if(background!=null)c.drawBitmap(background,null,new Rect(0,0,W,H),paint);else fill(c,0xff254d5d,0,0,W,H);}
    private void drawGear(Canvas c,float cx,float cy,int color){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);c.drawCircle(cx,cy,12,paint);c.drawCircle(cx,cy,4,paint);for(int i=0;i<8;i++){double a=Math.PI*i/4;float x1=cx+(float)Math.cos(a)*14,y1=cy+(float)Math.sin(a)*14,x2=cx+(float)Math.cos(a)*20,y2=cy+(float)Math.sin(a)*20;c.drawLine(x1,y1,x2,y2,paint);}paint.setStyle(Paint.Style.FILL);}
    private void drawWifi(Canvas c,float cx,float cy,int color){paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);RectF a=new RectF(cx-16,cy-16,cx+16,cy+16);c.drawArc(a,225,90,false,paint);RectF b=new RectF(cx-10,cy-10,cx+10,cy+10);c.drawArc(b,225,90,false,paint);paint.setStyle(Paint.Style.FILL);c.drawCircle(cx,cy+10,2,paint);}
    private void drawBack(Canvas c){paint.setColor(WHITE);paint.setStyle(Paint.Style.FILL);c.drawCircle(70,65,20,paint);paint.setColor(0xff333637);paint.setStrokeWidth(3);c.drawLine(77,65,63,65,paint);c.drawLine(63,65,70,58,paint);c.drawLine(63,65,70,72,paint);}
    private void drawCloud(Canvas c,float x,float y){paint.setColor(0xffe7f5fb);paint.setStyle(Paint.Style.FILL);c.drawCircle(x-20,y,19,paint);c.drawCircle(x,y-12,27,paint);c.drawCircle(x+25,y,18,paint);round(c,0xffe7f5fb,x-40,y,x+43,y+20,10);}

    private void drawHome(Canvas c){drawBackground(c);if(topFeaturesVisible)drawTopFeatures(c);text(c,new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()),1180,topFeaturesVisible?91:42,22,WHITE);
        round(c,0xb955a9b5,52,107,447,328,22);text(c,displayTemperature()+"°"+(fahrenheit?"F":"C"),73,183,62,WHITE);text(c,condition,73,219,23,0xffeaf4f4);text(c,"◉  "+cityLabel(city),75,289,18,0xffdaeeee);drawCloud(c,365,199);
        text(c,"常用应用",54, homeIconCount==10?443:488,25,WHITE);float left=52,cw=218,gap=24;int rows=homeIconCount==10?2:1;float ch=rows==2?82:118,top=rows==2?465:510,gapY=rows==2?18:0;for(int i=0;i<homeIconCount;i++){int row=i/5,col=i%5;float x=left+col*(cw+gap),y=top+row*(ch+gapY);round(c,selected==i?0xe421ad9c:0xc7667f82,x,y,x+cw,y+ch,12);strokeRound(c,selected==i?0xffd9fff6:0x5596b4b8,x+1,y+1,x+cw-1,y+ch-1,12,2);if(slots[i]!=null){float icon=rows==2?55:80;drawAppIcon(c,slots[i],x+cw/2-icon/2,y+6,(int)icon,(int)(icon*.9f));center(c,appLabel(slots[i]),x+cw/2,y+ch-(rows==2?8:15),rows==2?15:18,WHITE,cw-14);}else{if(selected==i)strokeRound(c,0xffeaf4f4,x+2,y+2,x+cw-2,y+ch-2,12,3);paint.setColor(0xffe5eded);paint.setStrokeWidth(rows==2?7:10);c.drawLine(x+cw/2-28,y+ch/2,x+cw/2+28,y+ch/2,paint);c.drawLine(x+cw/2,y+ch/2-28,x+cw/2,y+ch/2+28,paint);}}
        if(slotMenu){round(c,0xe92c3334,442,255,838,465,15);String[] opts={"替换应用","清空槽位","取消"};for(int i=0;i<3;i++){round(c,i==menuSelected?0xff147f80:0xff414849,482,280+i*57,798,326+i*57,8);center(c,opts[i],640,311+i*57,23,WHITE,300);}}
    }
    private void drawTopFeatures(Canvas c){int[] colors={0x9955747a,0x99617675,0x9955747a,0x995b737a,0x99676f70};for(int i=0;i<5;i++){float x=52+i*242,y=16;round(c,lastFocus==1&&topSelection==i?0xd923a997:colors[i],x,y,x+218,y+66,11);if(lastFocus==1&&topSelection==i)strokeRound(c,WHITE,x+1,y+1,x+217,y+65,11,3);drawFeatureIcon(c,i,x+38,y+33);center(c,TOP_FEATURES[i],x+137,y+41,20,WHITE,160);}}
    private void drawFeatureIcon(Canvas c,int index,float x,float y){paint.setColor(WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);if(index==0)drawWifi(c,x,y,WHITE);else if(index==1){c.drawRect(x-13,y-13,x+13,y+13,paint);c.drawLine(x-19,y-7,x-13,y-7,paint);c.drawLine(x+13,y-7,x+19,y-7,paint);c.drawLine(x-19,y+7,x-13,y+7,paint);c.drawLine(x+13,y+7,x+19,y+7,paint);c.drawLine(x-7,y-19,x-7,y-13,paint);c.drawLine(x+7,y-19,x+7,y-13,paint);c.drawLine(x-7,y+13,x-7,y+19,paint);c.drawLine(x+7,y+13,x+7,y+19,paint);}else if(index==2){c.drawRect(x-17,y-12,x+17,y+10,paint);c.drawLine(x-7,y+16,x+7,y+16,paint);c.drawLine(x,y+10,x,y+16,paint);}else if(index==3){c.drawRect(x-15,y-16,x+15,y+16,paint);c.drawLine(x-9,y-7,x+9,y-7,paint);c.drawLine(x-9,y,x+9,y,paint);c.drawLine(x-9,y+7,x+4,y+7,paint);}else{paint.setStyle(Paint.Style.FILL);drawGear(c,x,y,WHITE);}paint.setStyle(Paint.Style.FILL);}
    private String displayTemperature(){int value=fahrenheit?(int)Math.round(temperatureC*9/5.0+32):temperatureC;return String.valueOf(value);}
    private String appLabel(ResolveInfo r){String key=component(r);String label=labelCache.get(key);if(label==null){try{label=r.loadLabel(getContext().getPackageManager()).toString();}catch(Exception e){label="";}labelCache.put(key,label);}return label;}
    private void drawAppIcon(Canvas c,ResolveInfo r,float x,float y,int w,int h){try{String key=component(r);Drawable icon=iconCache.get(key);if(icon==null){icon=r.loadIcon(getContext().getPackageManager());iconCache.put(key,icon);}round(c,0x668da8ad,x-5,y-5,x+w+5,y+h+5,14);if(icon instanceof BitmapDrawable){BitmapDrawable bitmap=(BitmapDrawable)icon;bitmap.setAntiAlias(true);bitmap.setDither(true);bitmap.setFilterBitmap(true);}icon.setBounds((int)x,(int)y,(int)(x+w),(int)(y+h));icon.draw(c);}catch(Exception ignored){round(c,0xff627d86,x,y,x+w,y+h,12);}}

    private void drawApps(Canvas c){drawBackground(c);round(c,0xd295a5a8,52,48,1228,680,12);strokeRound(c,0x99e4f5f4,53,49,1227,679,12,2);int maxPage=Math.max(1,(apps.size()+19)/20);text(c,(page+1)+" / "+maxPage,1112,86,19,WHITE);float startX=70,startY=108,cellW=218,cellH=96,rowPitch=150,gapX=16;int[] colors={0xff159f89,0xff3579c9,0xffd49f1d,0xff098f81,0xffbac9ca,0xff356fbd,0xff176f69,0xff176f69};
        for(int n=0;n<20;n++){int index=page*20+n,row=n/5,col=n%5;float x=startX+col*(cellW+gapX),y=startY+row*rowPitch;boolean exists=index<apps.size();int color=exists?colors[index%colors.length]:0x55536366;round(c,selected==n?0xff1fc5b2:color,x,y,x+cellW,y+cellH,10);if(exists){ResolveInfo r=apps.get(index);drawAppIcon(c,r,x+cellW/2-34,y+7,68,62);round(c,0xa82c3d43,x+5,y+cellH+8,x+cellW-5,y+cellH+38,6);center(c,appLabel(r),x+cellW/2,y+cellH+30,19,WHITE,cellW-14);}if(selected==n)strokeRound(c,WHITE,x,y,x+cellW,y+cellH,10,3);}
        if(apps.isEmpty())center(c,"没有可用的第三方应用",640,390,26,WHITE,700);
    }
    private void drawSettings(Canvas c){drawDark(c);drawBack(c);center(c,"桌面设置",640,88,38,WHITE,600);String[] subtitle={"主屏幕与设备启动","开机自动恢复超级Home","MET Norway 天气服务","闲置时自动显示","自然风景照片","浏览和管理文件","本地 APK 安装","选择界面显示语言","首页显示 5 或 10 个应用","版本与开源信息"};for(int i=0;i<SETTING_NAMES.length;i++){int row=i/3,col=i%3;float x=74+col*386,y=112+row*112;round(c,i==selected?0xff247c7b:0xff363b3d,x,y,x+356,y+94,10);round(c,SETTING_COLORS[i],x+14,y+14,x+76,y+76,11);drawSettingIcon(c,i,x+45,y+45);text(c,SETTING_NAMES[i],x+91,y+39,22,WHITE);String detail=subtitle[i];if(i==1)detail=autoStart?"已开启":"已关闭";if(i==7)detail="en".equals(languageMode)?"英文":"zh".equals(languageMode)?"中文":"跟随系统";if(i==8)detail=homeIconCount==10?"10":"5";text(c,detail,x+91,y+68,16,0xffc0c9ca);if(i==1){round(c,autoStart?0xff16b89e:0xff6e7779,x+304,y+34,x+340,y+58,13);paint.setColor(WHITE);c.drawCircle(autoStart?x+330:x+314,y+46,9,paint);}}
        drawBrand(c);text(c,"MENU 返回桌面",50,680,18,0xffb8c1c2);
    }
    private void drawLanguage(Canvas c){drawDark(c);drawBack(c);center(c,"应用语言",640,118,38,WHITE,600);String[] labels={"跟随系统","中文","英文"};String[] details={"使用电视系统语言","简体中文界面","English interface"};for(int i=0;i<3;i++){float y=190+i*92;round(c,i==selected?0xff247c7b:0xff3a3d3e,222,y,1058,y+68,9);text(c,labels[i],260,y+30,23,WHITE);text(c,details[i],260,y+54,17,0xffb7c0c1);if((i==0&&"system".equals(languageMode))||(i==1&&"zh".equals(languageMode))||(i==2&&"en".equals(languageMode))){paint.setColor(0xff438bec);c.drawCircle(1018,y+34,11,paint);paint.setColor(WHITE);c.drawCircle(1018,y+34,5,paint);}}text(c,"选择后立即应用并保存",222,585,18,0xffaeb5b6);}
    private void drawIconCount(Canvas c){drawDark(c);drawBack(c);center(c,"首页图标数量",640,118,38,WHITE,600);String[] labels={"5 个","10 个"};String[] details={"显示一行五个应用槽位","显示两行共十个应用槽位"};for(int i=0;i<2;i++){float y=220+i*105;round(c,i==selected?0xff247c7b:0xff3a3d3e,222,y,1058,y+76,9);text(c,labels[i],260,y+33,24,WHITE);text(c,details[i],260,y+60,17,0xffb7c0c1);if((i==0&&homeIconCount==5)||(i==1&&homeIconCount==10)){paint.setColor(0xff438bec);c.drawCircle(1018,y+38,11,paint);paint.setColor(WHITE);c.drawCircle(1018,y+38,5,paint);}}text(c,"选择后立即应用并保存",222,510,18,0xffaeb5b6);}
    private void drawSettingIcon(Canvas c,int index,float x,float y){paint.setColor(WHITE);paint.setStrokeWidth(4);paint.setStyle(Paint.Style.STROKE);switch(index){case 0:c.drawCircle(x,y,18,paint);c.drawLine(x-5,y,x+5,y,paint);c.drawLine(x,y-5,x,y+5,paint);break;case 1:c.drawCircle(x,y,15,paint);c.drawLine(x-24,y,x+24,y,paint);break;case 2:c.drawCircle(x,y,19,paint);c.drawLine(x-12,y+18,x+12,y+18,paint);break;case 3:c.drawCircle(x,y,18,paint);c.drawLine(x,y-12,x,y+9,paint);break;case 4:c.drawRect(x-18,y-16,x+18,y+15,paint);c.drawLine(x-13,y+8,x-2,y-4,paint);c.drawLine(x-2,y-4,x+8,y+5,paint);break;case 5:case 6:c.drawRect(x-15,y-20,x+15,y+20,paint);c.drawLine(x-8,y-8,x+8,y-8,paint);c.drawLine(x-8,y,x+8,y,paint);break;case 7:c.drawCircle(x,y,18,paint);c.drawLine(x,y-11,x,y+6,paint);c.drawCircle(x,y+14,2,paint);break;default:c.drawCircle(x,y,16,paint);c.drawLine(x,y-25,x,y+25,paint);}paint.setStyle(Paint.Style.FILL);}
    private void drawAbout(Canvas c){drawDark(c);drawBack(c);center(c,"关于",640,100,38,WHITE,550);round(c,WHITE,550,159,730,339,8);try{Drawable logo=getResources().getDrawable(R.drawable.pctutu_icon);logo.setBounds(566,175,714,323);logo.draw(c);}catch(Exception ignored){}center(c,"超级桌面",640,405,36,WHITE,450);center(c,"com.pctutu.tvhome",640,445,21,0xffc4cccc,600);center(c,"版本 1.0.0",640,486,20,0xffc4cccc,450);center(c,"感谢：MET Norway 天气数据服务",640,541,19,0xffaeb8b9,650);drawBrand(c);}
    private void drawBrand(Canvas c){round(c,WHITE,1057,624,1112,679,4);try{Drawable logo=getResources().getDrawable(R.drawable.pctutu_icon);logo.setBounds(1064,631,1105,672);logo.draw(c);}catch(Exception ignored){}text(c,"PCTUTU.com",1123,661,19,WHITE);}
    private void drawDark(Canvas c){fill(c,0xff292c2d,0,0,W,H);}
    private void drawWeather(Canvas c){drawDark(c);drawBack(c);center(c,"天气设置",640,137,40,WHITE,550);weatherRow(c,0,"自动获取天气位置",autoWeather?"开":"关",234);weatherRow(c,1,"手动设置天气位置",city+"    ›",315);weatherRow(c,2,"摄氏度 / 华氏度切换",fahrenheit?"‹  华氏  ›":"‹  摄氏  ›",396);text(c,"天气来源：MET Norway Locationforecast",222,523,18,0xffaab0b1);text(c,"按 MENU 返回设置",222,565,16,0xff93999a);}
    private void weatherRow(Canvas c,int i,String label,String value,float y){round(c,i==selected?0xff5d6061:0xff3a3d3e,222,y,1058,y+66,10);text(c,label,250,y+42,22,0xffd6d8d8);if(i==0){round(c,autoWeather?0xff387bea:0xff6b7071,981,y+20,1034,y+47,14);paint.setColor(WHITE);c.drawCircle(autoWeather?1018:997,y+33,10,paint);}else{paint.setTextSize(20);paint.setColor(0xffd6d8d8);c.drawText(value,925,y+42,paint);}}
    private void drawCities(Canvas c){drawDark(c);drawBack(c);center(c,"选择全球城市",640,103,36,WHITE,600);round(c,0xff3a3e40,164,128,1116,190,8);text(c,cityInput+"_",190,168,24,WHITE);text(c,"输入英文或中文城市名，按 OK 查询全球天气",164,218,18,0xffbfc7c8);for(int i=0;i<CITIES.length;i++){int row=i/6,col=i%6;float x=164+col*162,y=245+row*70;round(c,selected==i?0xff3587e8:0xff3a3e40,x,y,x+144,y+54,9);center(c,CITIES[i],x+72,y+35,20,WHITE,132);}text(c,"DEL 删除   OK 查询   返回取消",164,590,18,0xffaeb5b6);}
    private void drawSaverSettings(Canvas c){drawDark(c);drawBack(c);center(c,"屏保设置",640,116,39,WHITE,600);String[] labels={"星空漫游","数字时钟","风景照片轮播","关闭屏保"};String[] details={"动态星空与柔和光点","全屏时钟与日期","使用内置自然风景照片","屏幕闲置时保持桌面"};for(int i=0;i<4;i++){float y=183+i*92;round(c,i==selected?0xff5d6061:0xff3a3d3e,222,y,1058,y+68,9);text(c,labels[i],250,y+29,22,WHITE);text(c,details[i],250,y+54,17,0xffb7c0c1);if(saverMode==i){paint.setColor(0xff438bec);c.drawCircle(1018,y+34,11,paint);paint.setColor(WHITE);c.drawCircle(1018,y+34,5,paint);}}text(c,"选择效果即可立即预览，返回后自动保存",222,605,18,0xffaeb5b6);}
    private void drawWallpaper(Canvas c){drawDark(c);drawBack(c);center(c,"壁纸选择",640,58,32,WHITE,500);ArrayList<Integer> list=wallpaperIndexes();float startX=95,startY=80,cellW=340,cellH=170,gapX=35,gapY=35;for(int n=0;n<list.size();n++){int row=n/3,col=n%3;float x=startX+col*(cellW+gapX),y=startY+row*(cellH+gapY);int index=list.get(n);Bitmap thumb=decodeThumbnail(WALLPAPERS[index]);if(thumb!=null)c.drawBitmap(thumb,null,new RectF(x,y,x+cellW,y+cellH),paint);else round(c,0xff343839,x,y,x+cellW,y+cellH,8);if(selected==n)strokeRound(c,WHITE,x-3,y-3,x+cellW+3,y+cellH+3,8,3);center(c,WALL_NAMES[index],x+cellW/2,y+cellH+24,18,WHITE,cellW-16);}text(c,"选择图片后应用为桌面壁纸",95,692,17,0xffafb4b5);}
    private Bitmap decodeThumbnail(int resource){for(int i=0;i<WALLPAPERS.length;i++)if(WALLPAPERS[i]==resource){if(thumbnailCache[i]==null){BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=4;try{thumbnailCache[i]=BitmapFactory.decodeResource(getResources(),resource,o);}catch(Exception ignored){}}return thumbnailCache[i];}return null;}
    private void drawFiles(Canvas c){drawDark(c);drawBack(c);center(c,isApkMode()?"APK 安装器":"文件管理",640,85,36,WHITE,650);text(c,currentDir==null?"":currentDir.getAbsolutePath(),150,128,17,0xffb3babb);round(c,0xff3a3e40,128,153,1152,628,8);if(fileError.length()>0)center(c,fileError,640,343,24,0xffd9dddd,900);else if(files.isEmpty())center(c,"此文件夹为空",640,350,23,0xffc0c6c7,800);for(int i=0;i<9&&fileScroll+i<files.size();i++){int index=fileScroll+i;File f=files.get(index);float y=164+i*50;round(c,index==fileSelection?0xff3c7c80:0x003a3e40,144,y,1136,y+43,5);text(c,f.isDirectory()?"▣":"▤",164,y+30,22,f.isDirectory()?0xff66c8b8:0xff73a6ef);String name=f.getName();paint.setTextSize(20);while(name.length()>1&&paint.measureText(name)>650)name=name.substring(0,name.length()-1);text(c,name,205,y+29,20,WHITE);text(c,f.isDirectory()?"文件夹":formatBytes(f.length()),890,y+28,16,0xffaeb6b7);text(c,"›",1080,y+30,25,0xffb9c1c2);}text(c,"↑↓选择   OK 打开   MENU 操作   返回上级",150,657,18,0xffc0c7c8);}
    private String formatBytes(long size){if(size<1024)return size+" B";if(size<1024*1024)return (size/1024)+" KB";return String.format(Locale.US,"%.1f MB",size/(1024f*1024f));}
    private void drawFileMenu(Canvas c){drawFiles(c);fill(c,0x99000000,0,0,W,H);round(c,0xff303536,420,235,860,485,10);center(c,selected==0?"重命名":"删除",640,310,28,WHITE,380);center(c,"文件操作",640,270,19,0xffafb9ba,380);center(c,"取消：返回",640,445,17,0xffb9c0c1,380);}
    private void drawDelete(Canvas c){drawFiles(c);fill(c,0x99000000,0,0,W,H);round(c,0xff303536,360,245,920,475,10);center(c,"确认删除此文件？",640,310,28,WHITE,520);center(c,selected==0?"删除":"取消",selected==0?530:750,410,22,selected==0?0xffff6565:WHITE,160);}
    private void drawRename(Canvas c){drawDark(c);drawBack(c);center(c,"重命名",640,96,36,WHITE,600);round(c,0xff3b4041,160,125,1120,184,8);text(c,renameText+"_",183,164,24,WHITE);String[] rows={"1234567890","QWERTYUIOP","ASDFGHJKL","ZXCVBNM"};for(int i=0;i<40;i++){int row=i/10,col=i%10;float x=175+col*94,y=213+row*75;boolean active=i==selected;round(c,active?0xff3587e8:0xff3d4243,x,y,x+80,y+58,7);if(col<rows[row].length())center(c,String.valueOf(rows[row].charAt(col)),x+40,y+39,22,WHITE,72);}String[] opts={"空格","删除字符","保存"};for(int i=0;i<3;i++){int ix=40+i;float x=300+i*235;round(c,selected==ix?0xff3587e8:0xff3d4243,x,530,x+200,586,8);center(c,opts[i],x+100,566,20,WHITE,180);}text(c,"MENU 取消",175,630,17,0xffafb7b8);}
    private void drawPreview(Canvas c){if(saverMode==2){drawBackground(c);int idx=(int)((System.currentTimeMillis()/6000)%WALLPAPERS.length);Bitmap b=decodeThumbnail(WALLPAPERS[idx]);if(b!=null)drawCrop(c,b,0,0,W,H);text(c,"风景照片轮播",50,70,22,WHITE);}else{fill(c,Color.BLACK,0,0,W,H);if(saverMode==0){for(int i=0;i<starX.length;i++){paint.setColor(i%5==0?0xffd0ebff:0xff8eaabd);c.drawCircle((starX[i]+(int)(System.currentTimeMillis()/80+i*3))%W,starY[i],i%3==0?2:1,paint);}center(c,"星空漫游",640,362,38,WHITE,500);}else{String clock=new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date());center(c,clock,640,360,92,WHITE,800);center(c,new SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(new Date()),640,411,26,0xffd5dadd,500);}}center(c,"按返回键结束预览",640,685,17,0xffb6babc,500);}
    private void drawCrop(Canvas c,Bitmap b,float l,float t,float r,float btm){float sw=b.getWidth(),sh=b.getHeight(),dw=r-l,dh=btm-t;float scale=Math.max(dw/sw,dh/sh),cw=dw/scale,ch=dh/scale;Rect src=new Rect((int)((sw-cw)/2),(int)((sh-ch)/2),(int)((sw+cw)/2),(int)((sh+ch)/2));paint.setFilterBitmap(true);c.drawBitmap(b,src,new RectF(l,t,r,btm),paint);}
    private void drawNotice(Canvas c){float width=Math.min(920,paint.measureText(notice)+75);round(c,0xe52d3435,(W-width)/2,612,(W+width)/2,674,10);center(c,notice,W/2,651,20,WHITE,width-20);}

    private int wallpaperResourceAt(int index){ArrayList<Integer> list=wallpaperIndexes();if(index<0||index>=list.size())return 0;return WALLPAPERS[list.get(index)];}
    private void adjustGridPage(int delta){page=Math.max(0,Math.min(Math.max(0,(apps.size()-1)/20),page+delta));}
    private void enterFileMode(boolean apk){state=FILES;loadFilesMode(actions.getStorageRoot(),apk);invalidate();}
    private void openSettings(){state=SETTINGS;selected=0;invalidate();}
    private void selectTopSettings(){openSettings();}

    @Override public boolean onTouchEvent(MotionEvent event){float x=event.getX()/scaleX,y=event.getY()/scaleY;if(event.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;downTime=System.currentTimeMillis();return true;}if(event.getAction()==MotionEvent.ACTION_UP){long elapsed=System.currentTimeMillis()-downTime;if(state==HOME){int rows=homeIconCount==10?2:1;float top=rows==2?465:510,ch=rows==2?82:118,gapY=rows==2?18:0;if(downY>=top&&downY<top+rows*(ch+gapY)){int col=(int)((x-52)/242),row=(int)((downY-top)/(ch+gapY)),index=row*5+col;if(col>=0&&col<5&&index<homeIconCount){selected=index;if(elapsed>500){slotMenu=true;menuSelected=0;}else if(slots[index]==null){slotTarget=index;state=APPS;page=0;selected=0;}else launch(slots[index]);invalidate();return true;}}}handleTap(x,y);return true;}return true;}
     private void handleTap(float x,float y){if(state==HOME){if(topFeaturesVisible&&y>=16&&y<=82&&x>=52&&x<=1240){int i=Math.min(4,(int)((x-52)/242));topSelection=i;lastFocus=1;openTopFeature(i);return;}if(x>=52&&x<=447&&y>=107&&y<=328){setNotice("天气  "+displayTemperature()+"  "+condition);return;}if(slotMenu){int op=(int)((y-280)/57);if(op<0||op>2)op=2;if(op==0){slotTarget=selected;state=APPS;page=0;selected=0;}else if(op==1)clearSlot(selected);slotMenu=false;invalidate();}invalidate();return;}
        if(state==APPS){if(y<96&&x>970){back();return;}float sx=70,sy=108;int col=Math.max(0,Math.min(4,(int)((x-sx)/234))),row=Math.max(0,Math.min(3,(int)((y-sy)/150)));selected=row*5+col;int ix=page*20+selected;if(ix<apps.size()){if(slotTarget>=0){int target=slotTarget;saveSlot(target,apps.get(ix));selected=target;slotTarget=-1;state=HOME;lastFocus=0;}else launch(apps.get(ix));}invalidate();return;}
         if(state==SETTINGS){if(x<120&&y<110){state=HOME;invalidate();return;}int col=Math.max(0,Math.min(2,(int)((x-74)/386))),row=Math.max(0,Math.min(3,(int)((y-112)/112))),index=row*3+col;if(index<SETTING_NAMES.length)openSetting(index);return;}
        if(state==WEATHER){if(x<120&&y<110){back();return;}int row=y<300?0:y<380?1:2;selected=row;if(row==0){autoWeather=!autoWeather;getContext().getSharedPreferences("surface_state",0).edit().putBoolean("auto_weather",autoWeather).apply();}else if(row==1){state=CITY_PICKER;selected=cityIndex();}else{fahrenheit=!fahrenheit;actions.setFahrenheit(fahrenheit);}invalidate();return;}
        if(state==CITY_PICKER){int col=Math.max(0,Math.min(5,(int)((x-164)/162))),row=Math.max(0,Math.min(2,(int)((y-245)/70)));int i=row*6+col;if(i<CITIES.length){selected=i;cityInput=CITIES[i];city=cityInput;actions.requestWeather(city);state=WEATHER;selected=1;}invalidate();return;}
        if(state==SAVER){if(x<120&&y<110){back();return;}selected=Math.max(0,Math.min(3,(int)((y-183)/92)));if(selected<3){saverMode=selected;getContext().getSharedPreferences("settings",0).edit().putInt("saver",saverMode).apply();showSaver(true);}else{getContext().getSharedPreferences("settings",0).edit().putInt("saver",3).apply();saverMode=3;}invalidate();return;}
         if(state==WALLPAPER){if(x>=95){int col=Math.max(0,Math.min(2,(int)((x-95)/375))),row=Math.max(0,Math.min(2,(int)((y-80)/205)));selected=row*3+col;chooseWallpaper();}invalidate();return;}
        if(state==FILES){if(x<120&&y<110){back();return;}if(y>=153&&y<628){fileSelection=fileScroll+Math.max(0,Math.min(8,(int)((y-164)/50)));activateFile();}invalidate();return;}
         if(state==ABOUT||state==LANGUAGE||state==ICON_COUNT){if(x<120&&y<110)back();if(state==LANGUAGE&&y>=190&&y<466){selected=Math.max(0,Math.min(2,(int)((y-190)/92)));languageMode=selected==0?"system":selected==1?"zh":"en";getContext().getSharedPreferences("surface_state",0).edit().putString("language",languageMode).apply();state=SETTINGS;}if(state==ICON_COUNT&&y>=220&&y<431){selected=y<325?0:1;homeIconCount=selected==0?5:10;getContext().getSharedPreferences("surface_state",0).edit().putInt("home_icon_count",homeIconCount).apply();state=SETTINGS;}invalidate();return;}
        if(state==FILE_MENU){if(x<560){renameText=selectedFile.getName();state=RENAME;selected=0;}else{state=DELETE;selected=0;}invalidate();return;}
        if(state==DELETE){selected=x<640?0:1;if(selected==0){removeTree(selectedFile);loadFiles(currentDir);}state=FILES;invalidate();return;}
        if(state==RENAME){if(y>=213&&y<513){int col=Math.max(0,Math.min(9,(int)((x-175)/94))),row=Math.max(0,Math.min(3,(int)((y-213)/75)));selected=row*10+col;handleRenameKey(KeyEvent.KEYCODE_DPAD_CENTER,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER),true);}else if(y>=530&&y<590){selected=x<500?40:x<735?41:42;handleRenameKey(KeyEvent.KEYCODE_DPAD_CENTER,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER),true);}invalidate();return;}
        if(state==PREVIEW){back();return;}}
}
