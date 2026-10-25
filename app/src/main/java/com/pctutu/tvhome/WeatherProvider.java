package com.pctutu.tvhome;

/** Injectable weather boundary. The launcher remains offline until a provider is supplied. */
public interface WeatherProvider {
    void request(Callback callback);

    interface Callback {
        void onWeather(WeatherData data);
        void onError(String message);
    }

    final class WeatherData {
        public final String city;
        public final String condition;
        public final int temperatureC;
        public final int humidity;

        public WeatherData(String city, String condition, int temperatureC, int humidity) {
            this.city = city;
            this.condition = condition;
            this.temperatureC = temperatureC;
            this.humidity = humidity;
        }
    }
}
