package com.cheatbreaker.client.radio;

import java.util.List;

public class RadioManager {
    public List<Station> stationList = RadioApi.loadStations();
    public RadioPlayerThread radioPlayerThread = new RadioPlayerThread();
    public Station station;

    public Station getStation() {
        return this.station;
    }

    public RadioPlayerThread getRadioPlayerThread() {
        return this.radioPlayerThread;
    }

    public void handleStation(Station station) {
        station.play();
        this.station = station;
    }

    public RadioManager() {
        // 电台功能已移除：不再启动播放线程、不再拉取 dash-api.com 的频道列表。
        // stationList 保持为空，RadioApi.isPlaying() 恒 false，界面里的电台面板不会出现。
    }

    public List<Station> getStationList() {
        return this.stationList;
    }
}
