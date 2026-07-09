package com.haiwancodex.www;

/**
 * 简单的Java示例类
 * 展示基本的Java编程概念
 */
public class SimpleExample {
    
    // 主方法 - 程序的入口点
    public static void main(String[] args) {
        System.out.println("=== 简单Java示例 ===");
        
        // 示例1: 变量和基本数据类型
        int number = 42;
        double pi = 3.14159;
        boolean isActive = true;
        String message = "Hello, World!";
        
        System.out.println("数字: " + number);
        System.out.println("圆周率: " + pi);
        System.out.println("是否激活: " + isActive);
        System.out.println("消息: " + message);
        
        // 示例2: 简单的计算
        int a = 10;
        int b = 20;
        int sum = a + b;
        int product = a * b;
        
        System.out.println("\n计算示例:");
        System.out.println(a + " + " + b + " = " + sum);
        System.out.println(a + " × " + b + " = " + product);
        
        // 示例3: 条件判断
        System.out.println("\n条件判断示例:");
        if (sum > 50) {
            System.out.println("和大于50，这是一个大数！");
        } else {
            System.out.println("和小于等于50");
        }
        
        // 示例4: 循环
        System.out.println("\n循环示例:");
        System.out.print("1到5的数字: ");
        for (int i = 1; i <= 5; i++) {
            System.out.print(i + " ");
        }
        System.out.println();
        
        // 示例5: 方法调用
        System.out.println("\n方法调用示例:");
        int result = calculateSquare(5);
        System.out.println("5的平方是: " + result);
        
        System.out.println("\n=== 示例结束 ===");
    }
    
    /**
     * 计算一个数的平方
     * @param number 要计算的数字
     * @return 平方结果
     */
    public static int calculateSquare(int number) {
        return number * number;
    }
}
