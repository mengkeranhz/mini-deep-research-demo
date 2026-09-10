package org.example;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** 入口：读入用户述求（命令行参数或交互输入），运行 Agent，打印最终结论。 */
public class Main {
    public static void main(String[] args) {
        String request;
        if (args.length > 0) {
            request = String.join(" ", args);
        } else {
            System.out.print("请输入研究述求: ");
            request = new Scanner(System.in, StandardCharsets.UTF_8).nextLine();
        }
        System.out.println("述求: " + request);
        try {
            String conclusion = new Agent().run(request);
            System.out.println("\n" + Console.header("================ 最终结论 ================"));
            System.out.println(conclusion);
        } catch (Exception e) {
            System.err.println(Console.error("执行失败: " + e.getMessage()));
            System.exit(1);
        }
    }
}
