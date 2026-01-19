package com.lizhi.method;

public class AABB {

    int x;
    int y;
    int width;
    int height;

    public AABB(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public boolean collidesWith(AABB order){
        return this.x + this.width / 2  >= order.x - order.width && this.y + this.height / 2 >= order.y - order.height / 2;
    }

}
