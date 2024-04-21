package net.minestom.server.inventory.click.type;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minestom.server.MinecraftServer;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.inventory.click.Click.Change.Cursor;
import net.minestom.server.inventory.click.Click.Change.Container;
import net.minestom.server.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.minestom.server.inventory.click.ClickUtils.assertClick;
import static net.minestom.server.inventory.click.ClickUtils.magic;

public class InventoryLeftDragTest {

    static {
        MinecraftServer.init();
    }

    @Test
    public void testNoCursor() {
        assertClick(List.of(), new Click.Info.LeftDrag(IntList.of(0)), List.of());
    }

    @Test
    public void testDistributeNone() {
        assertClick(
                List.of(new Cursor(magic(32))),
                new Click.Info.LeftDrag(IntList.of()),
                List.of()
        );
    }

    @Test
    public void testDistributeOne() {
        assertClick(
                List.of(new Cursor(magic(32))),
                new Click.Info.LeftDrag(IntList.of(0)),
                List.of(new Container(0, magic(32)), new Cursor(ItemStack.AIR))
        );
    }

    @Test
    public void testDistributeExactlyEnough() {
        assertClick(
                List.of(new Cursor(magic(32))),
                new Click.Info.LeftDrag(IntList.of(0, 1)),
                List.of(new Container(0, magic(16)), new Container(1, magic(16)), new Cursor(ItemStack.AIR))
        );

        assertClick(
                List.of(new Cursor(magic(30))),
                new Click.Info.LeftDrag(IntList.of(0, 1, 2)),
                List.of(
                        new Container(0, magic(10)),
                        new Container(1, magic(10)),
                        new Container(2, magic(10)),
                        new Cursor(ItemStack.AIR)
                )
        );
    }

    @Test
    public void testRemainderItems() {
        assertClick(
                List.of(new Cursor(magic(32))),
                new Click.Info.LeftDrag(IntList.of(0, 1, 2)),
                List.of(
                        new Container(0, magic(10)),
                        new Container(1, magic(10)),
                        new Container(2, magic(10)),
                        new Cursor(magic(2))
                )
        );

        assertClick(
                List.of(new Cursor(magic(25))),
                new Click.Info.LeftDrag(IntList.of(0, 1, 2, 3)),
                List.of(
                        new Container(0, magic(6)),
                        new Container(1, magic(6)),
                        new Container(2, magic(6)),
                        new Container(3, magic(6)),
                        new Cursor(magic(1))
                )
        );
    }

    @Test
    public void testDistributeOverExisting() {
        assertClick(
                List.of(new Container(0, magic(16)), new Cursor(magic(32))),
                new Click.Info.LeftDrag(IntList.of(0)),
                List.of(new Container(0, magic(48)), new Cursor(ItemStack.AIR))
        );
    }

    @Test
    public void testDistributeOverFull() {
        assertClick(
                List.of(new Container(0, magic(64)), new Cursor(magic(32))),
                new Click.Info.LeftDrag(IntList.of(0)),
                List.of()
        );
    }

}
