package com.pixelclub.teams;

import com.pixelclub.teams.store.*;
import java.util.*;
import java.sql.SQLException;

/** Immutable read view for display/combat; SQL remains the authority for every mutation. */
public final class TeamSnapshots {
    public record Subject(Team team,Member member,List<Member> members){}
    private record View(Map<Integer,Team> teams,Map<UUID,Member> people,Map<Integer,List<Member>> members,Map<UUID,String> names,Set<Long> allies,boolean healthy){}
    private final TeamStore store;
    private volatile View view=new View(Map.of(),Map.of(),Map.of(),Map.of(),Set.of(),false);
    public TeamSnapshots(TeamStore store){this.store=store;}
    public synchronized void refreshAll()throws SQLException{
        try{Map<Integer,Team> teams=new HashMap<>();for(var row:store.rows("teams",null,null)){Team team=new Team(row);teams.put(team.id(),team);}Map<UUID,String> names=new HashMap<>();for(var row:store.rows("player_cache",null,null))names.put(UUID.fromString(row.get("player_uuid").toString()),Objects.toString(row.get("player_name"),""));Map<UUID,Member> people=new HashMap<>();Map<Integer,List<Member>> members=new HashMap<>();for(var row:store.rows("team_members",null,null)){Map<String,Object> fields=new LinkedHashMap<>(row);UUID id=UUID.fromString(row.get("player_uuid").toString());if(fields.get("player_name")==null)fields.put("player_name",names.get(id));Member member=new Member(fields);if(!teams.containsKey(member.teamId()))throw new SQLException("Member references missing team");people.put(id,member);members.computeIfAbsent(member.teamId(),key->new ArrayList<>()).add(member);}view=new View(Map.copyOf(teams),Map.copyOf(people),freeze(members),Map.copyOf(names),readAllies(),true);}catch(SQLException|RuntimeException error){failed();throw error;}
    }
    public synchronized void refreshTeam(int id)throws SQLException{
        try{View old=view;Map<Integer,Team> teams=new HashMap<>(old.teams());Map<UUID,Member> people=new HashMap<>(old.people());Map<Integer,List<Member>> groups=new HashMap<>(old.members());for(Member member:groups.getOrDefault(id,List.of()))people.remove(member.uuid());Optional<Team> team=store.team(id);List<Member> members=team.isPresent()?List.copyOf(store.members(id)):List.of();if(team.isPresent()){teams.put(id,team.get());groups.put(id,members);for(Member member:members)people.put(member.uuid(),member);}else{teams.remove(id);groups.remove(id);}view=new View(Map.copyOf(teams),Map.copyOf(people),Map.copyOf(groups),old.names(),old.allies(),old.healthy());}catch(SQLException|RuntimeException error){failed();throw error;}
    }
    public synchronized void refreshAllies()throws SQLException{try{View old=view;view=new View(old.teams(),old.people(),old.members(),old.names(),readAllies(),old.healthy());}catch(SQLException|RuntimeException error){failed();throw error;}}
    public synchronized void remember(UUID id,String name){View old=view;Map<UUID,String> names=new HashMap<>(old.names());names.put(id,name);view=new View(old.teams(),old.people(),old.members(),Map.copyOf(names),old.allies(),old.healthy());}
    public synchronized void failed(){View old=view;view=new View(old.teams(),old.people(),old.members(),old.names(),old.allies(),false);}
    public boolean healthy(){return view.healthy();}
    public String name(UUID id){return view.names().get(id);}
    public Subject subject(UUID id){View current=view;Member member=current.people().get(id);Team team=member==null?null:current.teams().get(member.teamId());return new Subject(team,member,team==null?List.of():current.members().getOrDefault(team.id(),List.of()));}
    public com.pixelclub.teams.api.TeamsDamagePolicy.Decision policy(UUID attacker,UUID victim){
        var unknown=com.pixelclub.teams.api.TeamsDamagePolicy.Decision.UNKNOWN;View current=view;if(!current.healthy()||attacker==null||victim==null)return unknown;if(attacker.equals(victim))return com.pixelclub.teams.api.TeamsDamagePolicy.Decision.ALLOW;if(!current.names().containsKey(attacker)||!current.names().containsKey(victim))return unknown;Member am=current.people().get(attacker),bm=current.people().get(victim);Team a=am==null?null:current.teams().get(am.teamId()),b=bm==null?null:current.teams().get(bm.teamId());boolean deny=a!=null&&b!=null&&(a.id()==b.id()&&!b.pvp()||current.allies().contains(pair(a.id(),b.id())));return deny?com.pixelclub.teams.api.TeamsDamagePolicy.Decision.DENY:com.pixelclub.teams.api.TeamsDamagePolicy.Decision.ALLOW;
    }
    private Set<Long> readAllies()throws SQLException{Set<Long> pairs=new HashSet<>();for(var row:store.rows("team_allies",null,null))pairs.add(pair(((Number)row.get("team_id_1")).intValue(),((Number)row.get("team_id_2")).intValue()));return Set.copyOf(pairs);}
    private static long pair(int a,int b){return ((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);}
    private static Map<Integer,List<Member>> freeze(Map<Integer,List<Member>> groups){Map<Integer,List<Member>> result=new HashMap<>();groups.forEach((key,value)->result.put(key,List.copyOf(value)));return Map.copyOf(result);}
}
