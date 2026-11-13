package com.lizhi.realtime;

import com.lizhi.domain.po.AgvMap;
import com.lizhi.domain.po.AgvPath;
import com.lizhi.domain.po.AgvSnapshot;
import com.lizhi.domain.po.Node;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 实时状态快照（V2.1 实时通道）。
 * <p>
 * 与旧接口不同，这里的坐标点只保留 x/y，不带 A* 的 parent/g/h/cost 链，响应体更小；
 * 前端概览/地图页只需要车的位置、状态和路径坐标。
 * <p>
 * JSON 结构：{ timestamp, etag, map:{mapId,mapName,version,width,height}, cars:[...], paths:[{agvCode,x,y}] }
 */
@Data
public class LiveState implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 快照生成时间（ISO8601） */
    private String timestamp;
    /** 内容指纹；变化才需要推送/返回 200，相同可 304 */
    private String etag;
    /** 当前地图元信息 */
    private MapInfo map;
    /** 车辆实时状态（精简 plannedPath，不含 parent 链） */
    private List<CarState> cars;
    /** 车辆占用路径点（所有车合并，按 agvCode 区分） */
    private List<PathPoint> paths;

    @Data
    public static class MapInfo implements Serializable {
        private Integer mapId;
        private String mapName;
        private Integer version;
        private Integer width;
        private Integer height;

        public static MapInfo from(AgvMap map) {
            if (map == null) {
                return null;
            }
            MapInfo info = new MapInfo();
            info.setMapId(map.getMapId());
            info.setMapName(map.getMapName());
            info.setVersion(map.getVersion());
            info.setWidth(map.getWidth());
            info.setHeight(map.getHeight());
            return info;
        }
    }

    @Data
    public static class CarState implements Serializable {
        private String agvCode;
        private Integer x;
        private Integer y;
        private Integer direction;
        private String status;
        private Double battery;
        private Integer speed;
        private Integer taskId;
        private String description;
        private String lastUpdate;
        private Integer isCharge;
        private Integer pathIndex;
        private List<Point> plannedPath;

        public static CarState from(AgvSnapshot car) {
            CarState state = new CarState();
            state.setAgvCode(car.getAgvCode());
            state.setX(car.getX());
            state.setY(car.getY());
            state.setDirection(car.getDirection());
            state.setStatus(car.getStatus());
            state.setBattery(car.getBattery());
            state.setSpeed(car.getSpeed());
            state.setTaskId(car.getTaskId());
            state.setDescription(car.getDescription());
            state.setLastUpdate(car.getLastUpdate());
            state.setIsCharge(car.getIsCharge());
            state.setPathIndex(car.getPathIndex());
            state.setPlannedPath(toPoints(car.getPlannedPath()));
            return state;
        }

        private static List<Point> toPoints(List<Node> nodes) {
            if (nodes == null) {
                return null;
            }
            List<Point> points = new ArrayList<>(nodes.size());
            for (Node node : nodes) {
                if (node == null) {
                    continue;
                }
                points.add(new Point(node.getX(), node.getY()));
            }
            return points;
        }
    }

    @Data
    public static class Point implements Serializable {
        private Integer x;
        private Integer y;

        public Point() {
        }

        public Point(Integer x, Integer y) {
            this.x = x;
            this.y = y;
        }
    }

    @Data
    public static class PathPoint implements Serializable {
        private String agvCode;
        private Integer x;
        private Integer y;
        private String type;

        public static PathPoint from(AgvPath path) {
            PathPoint point = new PathPoint();
            point.setAgvCode(path.getAgvCode());
            point.setX(path.getX());
            point.setY(path.getY());
            point.setType(path.getType());
            return point;
        }
    }
}
