package kr.ioniq5.usbcp.setup;
import java.io.*;
import java.util.*;
import hmgapi.engine.Json;
public final class WelcomeMapTest {
 static void check(boolean value){if(!value)throw new AssertionError("welcome map");}
 public static void main(String[] args)throws Exception {
  File maps=new File(args[0]),source=new File(args[1]);check(maps.mkdirs());
  Setup.merge(maps,source);File target=Setup.target(maps);
  Map<String,Object> car=Setup.json(new File(target,"car.json"));
  check(Setup.list(car.get("apps")).contains("welcome"));
  check("33376e36199a38b928bceec54b0c148b".equals(Setup.map(car.get("files")).get("/system/app/WelcomeGoodbye.odex")));
  check(Setup.json(new File(target,"welcome.json")).equals(Setup.json(new File(source,"welcome.json"))));
  Setup.list(car.get("apps")).add("unrelated");Setup.write(new File(target,"car.json"),Json.write(car));
  Setup.merge(maps,source);check(Setup.list(Setup.json(new File(target,"car.json")).get("apps")).contains("unrelated"));
  car=Setup.json(new File(target,"car.json"));Setup.map(car.get("files")).put("/system/app/WelcomeGoodbye.odex","00000000000000000000000000000000");
  Setup.write(new File(target,"car.json"),Json.write(car));
  try{Setup.merge(maps,source);throw new AssertionError("wrong ODEX map must fail");}
  catch(IOException expected){check(expected.getMessage().equals("map file hash conflict"));}
  System.out.println("WelcomeMapTest PASS: fresh/merge preservation/target conflict");
 }
}
