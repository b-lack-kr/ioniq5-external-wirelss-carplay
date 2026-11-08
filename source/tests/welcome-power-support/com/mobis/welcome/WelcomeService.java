package com.mobis.welcome;
public class WelcomeService {
 public static int mode;public static boolean failMode;
 public static int getMode(){if(failMode)throw new IllegalStateException("PRIVATE");return mode;}
 public void e(int status){}
}
