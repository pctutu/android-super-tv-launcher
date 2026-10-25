package com.pctutu.tvhome;

import android.os.AsyncTask;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** MET Norway Locationforecast adapter. The caller supplies a city in English or Chinese. */
public final class MetNorwayWeatherProvider implements WeatherProvider {
    private static final Map<String, City> CITIES = new HashMap<String, City>();
    static {
        add("beijing", "北京", 39.9042, 116.4074); add("shanghai", "上海", 31.2304, 121.4737);
        add("guangzhou", "广州", 23.1291, 113.2644); add("shenzhen", "深圳", 22.5431, 114.0579);
        add("chengdu", "成都", 30.5728, 104.0668); add("chongqing", "重庆", 29.5630, 106.5516);
        add("wuhan", "武汉", 30.5928, 114.3055); add("xian", "西安", 34.3416, 108.9398);
        add("hangzhou", "杭州", 30.2741, 120.1551); add("nanjing", "南京", 32.0603, 118.7969);
        add("tianjin", "天津", 39.3434, 117.3616); add("qingdao", "青岛", 36.0671, 120.3826);
        add("shenyang", "沈阳", 41.8057, 123.4315); add("harbin", "哈尔滨", 45.8038, 126.5349);
        add("kunming", "昆明", 25.0389, 102.7183); add("xiamen", "厦门", 24.4798, 118.0894);
        add("hong kong", "香港", 22.3193, 114.1694); add("macau", "澳门", 22.1987, 113.5439);
    }
    private final String cityName;
    public MetNorwayWeatherProvider(String cityName) { this.cityName = cityName == null ? "北京" : cityName.trim(); }
    private static void add(String en, String zh, double lat, double lon) { City c = new City(zh, lat, lon); CITIES.put(en, c); CITIES.put(zh, c); }
    private City resolve() { City c = CITIES.get(cityName.toLowerCase(Locale.US)); return c == null ? null : c; }
    @Override public void request(final Callback callback) {
        new AsyncTask<Void, Void, Result>() {
            protected Result doInBackground(Void... ignored) {
                HttpURLConnection connection = null;
                try {
                    City city = resolve();
                    if (city == null) city = geocode(cityName);
                    if (city == null) return Result.error("city not found");
                    URL url = new URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + city.lat + "&lon=" + city.lon);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setInstanceFollowRedirects(true);
                    connection.setRequestProperty("User-Agent", "SuperHome/1.0 (com.pctutu.tvhome; contact: support@pctutu.com)");
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                    if (connection.getResponseCode() != 200) return Result.error("MET Norway HTTP " + connection.getResponseCode());
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
                    StringBuilder body = new StringBuilder(); String line; while ((line = reader.readLine()) != null) body.append(line); reader.close();
                    JSONObject root = new JSONObject(body.toString()); JSONArray series = root.getJSONObject("properties").getJSONArray("timeseries");
                    JSONObject forecast = series.getJSONObject(0).getJSONObject("data");
                    JSONObject details = forecast.getJSONObject("instant").getJSONObject("details");
                    String symbol = "";
                    JSONObject next = forecast.optJSONObject("next_1_hours");
                    if (next == null) next = forecast.optJSONObject("next_6_hours");
                    if (next != null) { JSONObject summary = next.optJSONObject("summary"); if (summary != null) symbol = summary.optString("symbol_code", ""); }
                    return Result.ok(new WeatherData(city.zh, conditionName(symbol), (int) Math.round(details.optDouble("air_temperature", 0)), (int) Math.round(details.optDouble("relative_humidity", 0))));
                } catch (Exception e) { return Result.error(e.getMessage() == null ? "weather request failed" : e.getMessage()); }
                finally { if (connection != null) connection.disconnect(); }
            }
            protected void onPostExecute(Result result) { if (result.data != null) callback.onWeather(result.data); else callback.onError(result.error); }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private City geocode(String query) throws Exception {
        String encoded = URLEncoder.encode(query, "UTF-8");
        URL url = new URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + encoded);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("User-Agent", "SuperHome/1.0 (com.pctutu.tvhome; contact: support@pctutu.com)");
        c.setConnectTimeout(12000); c.setReadTimeout(12000);
        if (c.getResponseCode() != 200) return null;
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder b = new StringBuilder(); String line; while ((line=r.readLine())!=null) b.append(line); r.close(); c.disconnect();
        JSONArray results = new JSONArray(b.toString()); if (results.length()==0) return null;
        JSONObject item = results.getJSONObject(0); double lat=item.optDouble("lat",Double.NaN), lon=item.optDouble("lon",Double.NaN);
        if (Double.isNaN(lat)||Double.isNaN(lon)) return null;
        return new City(query, lat, lon);
    }
    private static String conditionName(String symbol) {
        String value = symbol == null ? "" : symbol.toLowerCase(Locale.US);
        if (value.contains("thunder")) return "雷雨";
        if (value.contains("sleet")) return "雨夹雪";
        if (value.contains("snow")) return value.contains("heavy") ? "大雪" : "降雪";
        if (value.contains("rain")) return value.contains("heavy") ? "大雨" : value.contains("light") ? "小雨" : "有雨";
        if (value.contains("drizzle")) return "毛毛雨";
        if (value.contains("fog")) return "雾";
        if (value.contains("cloudy")) return "阴";
        if (value.contains("partlycloudy")) return "多云";
        if (value.contains("fair")) return "晴间多云";
        if (value.contains("clearsky")) return "晴";
        return "天气已更新";
    }
    private static final class City { final String zh; final double lat, lon; City(String z, double a, double o) { zh=z;lat=a;lon=o; } }
    private static final class Result { final WeatherData data; final String error; private Result(WeatherData d,String e){data=d;error=e;} static Result ok(WeatherData d){return new Result(d,null);} static Result error(String e){return new Result(null,e);} }
}
