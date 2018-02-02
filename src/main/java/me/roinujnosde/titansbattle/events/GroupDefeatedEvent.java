/*
 * The MIT License
 *
 * Copyright 2017 Edson Passos - edsonpassosjr@outlook.com.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.roinujnosde.titansbattle.events;

import com.massivecraft.factions.entity.Faction;
import me.roinujnosde.titansbattle.TitansBattle;
import net.sacredlabyrinth.phaed.simpleclans.Clan;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 *
 * @author RoinujNosde
 */
public class GroupDefeatedEvent extends Event {

    private final static HandlerList HANDLERS = new HandlerList();
    private TitansBattle plugin;
    private Object group;
    private Player lastParticipant;

    private GroupDefeatedEvent() {
    }

    public GroupDefeatedEvent(Object group, Player lastParticipant) {
        plugin = TitansBattle.getInstance();
        if (lastParticipant == null) {
            throw new IllegalArgumentException("Player cannot be null.");
        }
        if (group == null) {
            throw new IllegalArgumentException("Group must be an instance of Clan or Faction and cannot be null.");
        }
        if (plugin.isSimpleClans()) {
            if (!(group instanceof Clan)) {
                throw new IllegalArgumentException("Group must be an instance of Clan or Faction and cannot be null.");
            }
        }
        if (plugin.isFactions()) {
            if (!(group instanceof Faction)) {
                throw new IllegalArgumentException("Group must be an instance of Clan or Faction and cannot be null.");
            }
        }
        this.group = group;
        this.lastParticipant = lastParticipant;
    }

    public Object getGroup() {
        return group;
    }

    public Player getLastParticipant() {
        return lastParticipant;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

}
