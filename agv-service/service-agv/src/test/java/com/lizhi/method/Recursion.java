package com.lizhi.method;

import java.util.Arrays;
import java.util.LinkedList;

public class Recursion {
    /**
     * 阶乘
     * @param n
     * @return
     */
    private int f(int n){
        if (n == 1){
            return 1;
        }
        return n * f(n - 1);
    }

    /**
     * 反向打印字符串
     * @param str
     * @param index
     */
    private void f1(String str,Integer index){
      if (index == -1){
          return;
      }
      System.out.println(str.charAt(index));
      f1(str,index - 1);
    }

    /**
     * 二分查找（递归版）
     * @param list
     * @param target
     * @param i
     * @param j
     * @return
     */
    private boolean f2(int[] list,Integer target,int i,int j){
        if (i > j){
            return false;
        }
        int m = (i+j) >>> 1;
        if (target < list[m]){
            return f2(list,target,i,m - 1);
        }else if (target > list[m]){
            return f2(list,target,m + 1,j);
        }else {
            return true;
        }
    }

    /**
     * 冒泡排序
     * @param list
     * @param j
     */
    private void f3(int[] list,int j){
        if (j == 0){
            return;
        }
        int x = 0;
        for (int i = 0; i < j; i++){
            if (list[i] > list[i+1]){
                int a = list[i];
                list[i] = list[i + 1];
                list[i + 1] = a;
                x = i;
            }
        }
        f3(list,x);
    }

    /**
     * 插入排序
     * @param list
     * @param low
     */
    private void f4(int[] list,int low){
        if (low == list.length - 1){
            return;
        }
        int i = low - 1;
        int t = list[low];
        while (i >= 0 && t < list[i]){
            list[i+1] = list[i];
            i--;
        }
        if (i+1 != low){
            list[i+1] = t;
        }
        f4(list,low+1);
    }

    private int fibonacci(int n){
        //初始化cache
        int[] cache = new int[n + 1];
        Arrays.fill(cache,-1);
        cache[0] = 0;
        cache[1] = 1;
        return f5(n,cache);
    }

    /**
     * 斐波那契数列
     * @param n
     * @return
     */
    private int f5(int n,int[] cache){
        if (cache[n] != -1){
            return cache[n];
        }
        int x = f5(n - 1,cache);
        int y = f5(n - 2,cache);
        cache[n] = x + y;
        return cache[n];
    }

    /**
     * 计算前n项之和
     * @param n
     * @return
     */
    private long f6(int n){
        if (n == 0){
            return 0;
        }
        return f6(n-1) + n;
    }

    /**
     * 计算前n项之和
     * @param n
     * @return
     */
    private long f7(int n){
        int sum = 0;
        for (int i = n; i > 0; i--){
            sum += i;
        }
        return sum;
    }

    static LinkedList<Integer> a = new LinkedList<>();
    static LinkedList<Integer> b = new LinkedList<>();
    static LinkedList<Integer> c = new LinkedList<>();

    static void init(int n){
        for (int i = n;i > 0;i--){
            a.addLast(i);
        }
    }
    private void print(){
        System.out.println(a);
        System.out.println(b);
        System.out.println(c);
        System.out.println("------------");
    }

    /**
     * 汉诺塔
     * @param a
     * @param b
     * @param c
     * @param n
     */
    private void f8(LinkedList<Integer> a,LinkedList<Integer> b,LinkedList<Integer> c,Integer n){
        if (n == 2){
            b.addLast(a.removeLast());
            c.addLast(a.removeLast());
            c.addLast(b.removeLast());
            return;
        }
        f8(a,c,b,n-1);//把a中n-1个移动到b
        c.addLast(a.removeLast());//中间步骤，把a中1个移动到c
        f8(b,a,c,n-1);//把b中n-1个移动到c
    }

    /**
     * 计算（i，j）点元素
     * @param i
     * @param j
     * @return
     */
    private int element(int i,int j,int[][] trangle){
        if (trangle[i][j] > 0){
            return trangle[i][j];
        }
        if (j == 0 || i == j){
            trangle[i][j] = 1;
        }else {
            trangle[i][j] = element(i-1,j-1,trangle) + element(i-1,j,trangle);
        }
        return trangle[i][j];
    }

    private void printBlank(int n,int i){
        for (int j = 0; j < 2*(n-1-i); j++){
            System.out.print(" ");
        }
    }

    /**
     * 打印元素
     * @param n
     */
    private void printElement(int n,int[][] triangle){
        for (int i = 0; i < n; i++){
            triangle[i] = new int[i + 1];
            printBlank(n,i);
            for (int j = 0; j <= i; j++){
                System.out.printf("%-4d",element(i, j,triangle));
            }
            System.out.println();
        }
    }

    /**
     * 创建一维数组
     * @param row
     * @param i
     */
    private void createRow(int[] row,int i){
        if (i == 0){
            row[0] = 1;
            return;
        }
        for (int j = i; j > 0; j--){
            row[j] = row[j] + row[j-1];
        }
    }

    /**
     * 打印元素
     * @param n
     */
    private void printRow(int n,int[] row){
        for (int i = 0; i < n; i++){
            printBlank(n,i);
            createRow(row,i);
            for (int j = 0; j <= i; j++){
                System.out.printf("%-4d",row[j]);
            }
            System.out.println();
        }
    }

    public void function(){
        int n = 10;
        int[] row = new int[n];
        printRow(n,row);
    }
}
