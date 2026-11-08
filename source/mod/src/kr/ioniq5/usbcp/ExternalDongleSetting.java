// SPDX-License-Identifier: AGPL-3.0-or-later
package kr.ioniq5.usbcp;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.HashSet;

/** Startup-only setting. No JSON evaluation, settings writes or hook-thread file I/O. */
public final class ExternalDongleSetting {
    public static final String PATH="/data/hmgapi/settings/neev-usb-carplay.json";
    static final int MAX_BYTES=65536,MAX_DEPTH=16;
    private ExternalDongleSetting() {}
    public static boolean read(File file)throws IOException {
        if(!file.getAbsoluteFile().equals(file.getCanonicalFile()))throw new IOException("setting path");
        // File.exists() also returns false for a dangling symlink. A directory
        // entry without an existing target is malformed, not a missing setting.
        for(File cursor=file;cursor!=null;cursor=cursor.getParentFile())if(!cursor.exists()) {
            File parent=cursor.getParentFile();if(parent!=null&&parent.isDirectory()) {
                String[] names=parent.list();if(names==null)throw new IOException("setting path");
                for(String name:names)if(name.equals(cursor.getName()))throw new IOException("setting path");
            }
        }
        if(!file.exists())return true;
        if(!file.isFile()||file.length()>MAX_BYTES)throw new IOException("setting size");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();InputStream in=new FileInputStream(file);
        try {byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>MAX_BYTES)throw new IOException("setting size");bytes.write(b,0,n);}}
        finally {in.close();}
        try {
            String text=Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return new Parser(text).setting();
        }catch(CharacterCodingException bad){throw new IOException("setting encoding");}
    }
    public static void main(String[] args) {
        boolean valid=false,enabled=false;
        try {if(args.length!=0)throw new IOException("arguments");enabled=read(new File(PATH));valid=true;}
        catch(IOException invalid) { /* Fixed category only, never settings values. */ }
        System.out.println(valid?(enabled?"ON":"OFF"):"INVALID");
        if(!valid)System.exit(2);
    }
    /** Small strict JSON recognizer: validates the entire bounded document, retaining only one boolean. */
    static final class Parser {
        final String text;int at;boolean enabled=true;
        Parser(String text){this.text=text;}
        boolean setting()throws IOException {space();object(0);space();if(at!=text.length())bad();return enabled;}
        void bad()throws IOException {throw new IOException("setting JSON");}
        void space(){while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        boolean take(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        void need(char c)throws IOException {if(!take(c))bad();}
        void object(int depth)throws IOException {
            if(depth>MAX_DEPTH)bad();need('{');HashSet<String> keys=new HashSet<String>();
            if(take('}'))return;
            do {
                String key=string();if(!keys.add(key))bad();need(':');space();
                if(depth==0&&"external_dongle".equals(key)) {
                    if(word("true"))enabled=true;else if(word("false"))enabled=false;else bad();
                }else value(depth+1);
                if(take('}'))return;need(',');
            }while(true);
        }
        boolean word(String token){space();if(text.startsWith(token,at)){at+=token.length();return true;}return false;}
        void value(int depth)throws IOException {
            if(depth>MAX_DEPTH)bad();space();if(at>=text.length())bad();char c=text.charAt(at);
            if(c=='{'){object(depth);return;}if(c=='"'){string();return;}
            if(c=='['){at++;if(take(']'))return;do {value(depth+1);if(take(']'))return;need(',');}while(true);}
            if(word("true")||word("false")||word("null"))return;
            number();
        }
        String string()throws IOException {
            need('"');StringBuilder out=new StringBuilder();
            while(at<text.length()) {
                char c=text.charAt(at++);if(c=='"')return out.toString();if(c<32)bad();
                if(c=='\\') {
                    if(at>=text.length())bad();c=text.charAt(at++);
                    if(c=='u') {
                        if(at+4>text.length())bad();int n=0;
                        for(int i=0;i<4;i++){char h=text.charAt(at++);int v=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;if(v<0)bad();n=n*16+v;}c=(char)n;
                    }else if(c=='b')c='\b';else if(c=='f')c='\f';else if(c=='n')c='\n';else if(c=='r')c='\r';else if(c=='t')c='\t';
                    else if(c!='"'&&c!='\\'&&c!='/')bad();
                }
                out.append(c);
            }
            bad();return null;
        }
        boolean digit(){return at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9';}
        void digits()throws IOException {if(!digit())bad();while(digit())at++;}
        void number()throws IOException {
            if(at<text.length()&&text.charAt(at)=='-')at++;
            if(at<text.length()&&text.charAt(at)=='0')at++;else digits();
            if(at<text.length()&&text.charAt(at)=='.'){at++;digits();}
            if(at<text.length()&&(text.charAt(at)=='e'||text.charAt(at)=='E')){at++;if(at<text.length()&&(text.charAt(at)=='+'||text.charAt(at)=='-'))at++;digits();}
        }
    }
}
