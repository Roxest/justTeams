package fr.xephi.authme.api.v3;
import org.bukkit.entity.Player;
public final class AuthMeApi {
    public static boolean loggedIn;
    public static int calls;
    public static final AuthMeApi INSTANCE=new AuthMeApi();
    public static AuthMeApi getInstance(){return INSTANCE;}
    public boolean isAuthenticated(Player player){calls++;return loggedIn;}
}
