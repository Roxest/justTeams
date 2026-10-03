package net.impactdev.impactor.api.economy.accounts;
import java.util.UUID;
/** Reflection-compatible identity only; no runtime provider implementation. */
public interface Account {UUID owner();boolean virtual();}
