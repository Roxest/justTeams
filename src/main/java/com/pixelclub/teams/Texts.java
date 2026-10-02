package com.pixelclub.teams;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.core.RegistryAccess;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import java.util.Map;
import java.util.Locale;

/** Legacy donor colours without enabling bold on Thai labels. */
public final class Texts {
    private Texts(){}
    public static String plain(String text){return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(legacyToMini(text)));}
    public static String tagMarkup(String text){var tags=TagResolver.builder().resolver(net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color()).resolver(net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient()).resolver(net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow()).resolver(net.kyori.adventure.text.minimessage.tag.standard.StandardTags.reset()).build();var mini=MiniMessage.builder().tags(tags).build();return mini.serialize(mini.deserialize(legacyToMini(text.replace("<bold>","").replace("</bold>","").replace("<b>","").replace("</b>",""))));}
    public static Component template(String format,Map<String,String> plain,Map<String,String> trusted) {
        var tags=TagResolver.builder();plain.forEach((key,value)->tags.resolver(Placeholder.unparsed(key,value)));trusted.forEach((key,value)->tags.resolver(Placeholder.component(key,MiniMessage.miniMessage().deserialize(legacyToMini(value)))));
        String source=legacyToMini(format.replace("<bold>","").replace("</bold>","").replace("<b>","").replace("</b>",""));
        String json=GsonComponentSerializer.gson().serialize(MiniMessage.miniMessage().deserialize(source,tags.build()));Component component=Component.Serializer.fromJson(json,RegistryAccess.EMPTY);return component==null?Component.literal(format):component;
    }
    public static Component of(String input) {
        String text=input==null?"":input;
        if(text.indexOf('<')>=0) {
            try {String safe=text.replace("<bold>","").replace("</bold>","").replace("<b>","").replace("</b>","");String json=GsonComponentSerializer.gson().serialize(MiniMessage.miniMessage().deserialize(legacyToMini(safe)));Component result=Component.Serializer.fromJson(json,RegistryAccess.EMPTY);if(result!=null)return result;}
            catch(RuntimeException ignored){}
        }
        MutableComponent result=Component.empty();Style style=Style.EMPTY;StringBuilder part=new StringBuilder();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if((c=='&'||c=='§')&&i+1<text.length()) {
                char code=Character.toLowerCase(text.charAt(i+1));
                if(code=='x'&&i+13<text.length()) {
                    StringBuilder hex=new StringBuilder();boolean valid=true;
                    for(int j=0;j<6;j++) { int at=i+2+j*2;if((text.charAt(at)!='&'&&text.charAt(at)!='§')||Character.digit(text.charAt(at+1),16)<0){valid=false;break;}hex.append(text.charAt(at+1)); }
                    if(valid){append(result,part,style);style=Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(hex.toString(),16)));i+=13;continue;}
                }
                if(code=='#'&&i+7<text.length()) {
                    String hex=text.substring(i+2,i+8);
                    if(hex.matches("[0-9a-fA-F]{6}")){append(result,part,style);style=Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(hex,16)));i+=7;continue;}
                }
                ChatFormatting format=ChatFormatting.getByCode(code);
                if(format!=null) {
                    append(result,part,style);i++;
                    if(format==ChatFormatting.RESET)style=Style.EMPTY;
                    else if(format.isColor())style=Style.EMPTY.withColor(format);
                    else if(format!=ChatFormatting.BOLD&&format!=ChatFormatting.OBFUSCATED)style=style.applyFormat(format);
                    continue;
                }
            }
            part.append(c);
        }
        append(result,part,style);return result;
    }
    private static void append(MutableComponent target,StringBuilder part,Style style){if(part.length()>0){target.append(Component.literal(part.toString()).setStyle(style));part.setLength(0);}}
    private static String legacyToMini(String value) {
        StringBuilder result=new StringBuilder();for(int i=0;i<value.length();i++){
            char marker=value.charAt(i);if((marker=='&'||marker=='§')&&i+1<value.length()){
                char code=Character.toLowerCase(value.charAt(i+1));
                if(code=='x'&&i+13<value.length()){StringBuilder hex=new StringBuilder();boolean valid=true;for(int j=0;j<6;j++){int at=i+2+j*2;if((value.charAt(at)!='&'&&value.charAt(at)!='§')||Character.digit(value.charAt(at+1),16)<0){valid=false;break;}hex.append(value.charAt(at+1));}if(valid){result.append("<reset><#").append(hex).append('>');i+=13;continue;}}
                if(code=='#'&&i+7<value.length()&&value.substring(i+2,i+8).matches("[0-9a-fA-F]{6}")){result.append("<reset><#").append(value,i+2,i+8).append('>');i+=7;continue;}
                ChatFormatting format=ChatFormatting.getByCode(code);if(format!=null){i++;if(format.isColor())result.append("<reset><").append(format.getName()).append('>');else if(format==ChatFormatting.RESET)result.append("<reset>");else if(format!=ChatFormatting.BOLD&&format!=ChatFormatting.OBFUSCATED)result.append('<').append(format.getName()).append('>');continue;}
            }result.append(marker);
        }return result.toString();
    }
    public static String money(double amount){return String.format(Locale.ROOT,"%,.2f",amount);}
}
