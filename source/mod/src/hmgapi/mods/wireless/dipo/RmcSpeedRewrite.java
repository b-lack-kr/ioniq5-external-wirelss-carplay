package hmgapi.mods.wireless.dipo;

/** Strict, bounded output-sentence transform. Never stores a position or changes
 * any byte except RMC field7 (knots) and its checksum. Caller owns session gates. */
final class RmcSpeedRewrite {
    static final int INVALID=1,NO_FIX=2,REPLACED=3,UNCHANGED=4;
    static final class Result {
        final String sentence; final int status,inputKmhMilli,outputKmhMilli;
        Result(String s,int code,int input,int output){sentence=s;status=code;inputKmhMilli=input;outputKmhMilli=output;}
    }
    private static Result no(String s,int status){return new Result(s,status,-1,-1);}
    private static boolean decimal(String s,int max){
        if(s.isEmpty()||s.length()>max)return false;boolean dot=false,digit=false;
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='.'&&!dot&&i>0&&i<s.length()-1){dot=true;continue;}if(c<'0'||c>'9')return false;digit=true;}
        return digit;
    }
    private static boolean digits(String s,int count){
        if(s.length()!=count)return false;for(int i=0;i<count;i++)if(s.charAt(i)<'0'||s.charAt(i)>'9')return false;return true;
    }
    private static boolean time(String s){
        if(s.length()<6||s.length()>13||!digits(s.substring(0,6),6))return false;
        if(s.length()>6&&(s.charAt(6)!='.'||s.length()==7||!digits(s.substring(7),s.length()-7)))return false;
        return Integer.parseInt(s.substring(0,2))<24&&Integer.parseInt(s.substring(2,4))<60&&Integer.parseInt(s.substring(4,6))<=60;
    }
    private static boolean coordinate(String s,int degrees){
        if(s.length()<degrees+2||!decimal(s,16)||!digits(s.substring(0,degrees+2),degrees+2))return false;
        if(s.length()>degrees+2&&s.charAt(degrees+2)!='.')return false;
        double minutes=Double.parseDouble(s.substring(degrees));int d=Integer.parseInt(s.substring(0,degrees));
        return minutes<60&&d<=(degrees==2?90:180)&&(d<(degrees==2?90:180)||minutes==0);
    }
    private static int hex(char c){return c>='0'&&c<='9'?c-'0':c>='A'&&c<='F'?c-'A'+10:c>='a'&&c<='f'?c-'a'+10:-1;}
    static Result apply(String s,int kmh){
        if(s==null||s.length()<20||s.length()>256||!s.startsWith("$GPRMC,")||kmh<0||kmh>=511)return no(s,INVALID);
        int star=s.indexOf('*');if(star<0||star+3>s.length()||s.indexOf('*',star+1)>=0)return no(s,INVALID);
        String suffix=s.substring(star+3);
        if(!(suffix.isEmpty()||suffix.equals("\r\n")||suffix.equals("\u0000")))return no(s,INVALID);
        int hi=hex(s.charAt(star+1)),lo=hex(s.charAt(star+2)),sum=0;
        if(hi<0||lo<0)return no(s,INVALID);
        for(int i=1;i<star;i++){char c=s.charAt(i);if(c<32||c>126||c=='$')return no(s,INVALID);sum^=c;}
        if(sum!=((hi<<4)|lo))return no(s,INVALID);
        String[] f=s.substring(0,star).split(",",-1);
        if(f.length!=12&&f.length!=13)return no(s,INVALID);
        if(!f[2].equals("A"))return no(s,f[2].equals("V")?NO_FIX:INVALID);
        // Empty mode is emitted by this OEM formatter. Explicit N/E/M/S means
        // invalid/estimated/manual/simulated position and is not promoted.
        if(f.length==13&&!f[12].isEmpty()&&!f[12].equals("A")&&!f[12].equals("D")&&!f[12].equals("F")&&!f[12].equals("R"))return no(s,NO_FIX);
        if(!time(f[1])||!coordinate(f[3],2)||!(f[4].equals("N")||f[4].equals("S"))
                ||!coordinate(f[5],3)||!(f[6].equals("E")||f[6].equals("W"))
                ||!decimal(f[7],12)||(!f[8].isEmpty()&&(!decimal(f[8],12)||Double.parseDouble(f[8])>=360))
                ||!digits(f[9],6)||Integer.parseInt(f[9].substring(0,2))<1||Integer.parseInt(f[9].substring(0,2))>31
                ||Integer.parseInt(f[9].substring(2,4))<1||Integer.parseInt(f[9].substring(2,4))>12
                ||(!f[10].isEmpty()&&(!decimal(f[10],12)||Double.parseDouble(f[10])>180))
                ||(f[10].isEmpty()!=f[11].isEmpty())||(!f[11].isEmpty()&&!f[11].equals("E")&&!f[11].equals("W")))return no(s,INVALID);
        double original=Double.parseDouble(f[7])*1.852;
        if(original>=511)return no(s,INVALID);
        // Integer rational conversion avoids locale/scientific formatting.
        long knotsMilli=Math.round(kmh*1000000d/1852d);
        String speed=(knotsMilli/1000)+"."+String.format(java.util.Locale.US,"%03d",knotsMilli%1000);
        int begin=0;for(int field=0;field<7;field++)begin=s.indexOf(',',begin)+1;
        int end=s.indexOf(',',begin);
        String body=s.substring(0,begin)+speed+s.substring(end,star);sum=0;
        for(int i=1;i<body.length();i++)sum^=body.charAt(i);
        String out=body+"*"+"0123456789ABCDEF".charAt(sum>>4)+"0123456789ABCDEF".charAt(sum&15)+suffix;
        return new Result(out,out.equals(s)?UNCHANGED:REPLACED,(int)Math.round(original*1000),
                (int)Math.round(knotsMilli*1.852));
    }
}
