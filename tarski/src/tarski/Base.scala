/* Base: Standard Java items (primitives, java.lang stuff, etc.)
 *
 * Base includes special objects for any part of the standard library that
 * interacts directly with language semantics.  For example, Java arrays are
 * part of the language but inherit from the standard library interface
 * Serializable, and Java foreach loops know about the interface Iterable.
 * Most of these objects must be present in the environment for the tarski
 * engine to work reasonably.
 *
 * In addition to standard class and interface definitions, Base includes a
 * few objects for the environment that the Java language treats as primitive
 * but eddy thinks of as normal objects.  These include the primitive types
 * such as boolean and int, which eddy looks up in the environment rather than
 * treating as separate tokens.  It also includes explicit literal objects for
 * true, false, and null.  By including these in the environment, the same
 * mechanism used to correct typos in identifiers can turn treu into true.
 */

package tarski

import tarski.AST._
import tarski.Denotations.{BooleanLit, NullLit}
import tarski.Environment.Env
import tarski.Items._
import tarski.JavaItems._
import tarski.Types._
import utility.Utility._

object Base {
  // Basic packages
  val JavaPkg = RootPackage("java")
  val JavaLangPkg = ChildPackage(JavaPkg,"lang")
  val JavaIoPkg = ChildPackage(JavaPkg,"io")

  // Basic interfaces and classes
  val CloneableItem = NormalInterfaceItem("Cloneable",JavaLangPkg)
  val SerializableItem = NormalInterfaceItem("Serializable",JavaIoPkg)
  val CharSequenceItem = NormalInterfaceItem("CharSequence",JavaLangPkg)
  val ComparableItem = {
    val T = SimpleTypeVar("T")
    NormalInterfaceItem("Comparable",JavaLangPkg,List(T))
  }
  private def comparable(t: RefType): ClassType = GenericType(ComparableItem,List(t),JavaLangPkg)

  // Basic reference types
  val StringType       = StringItem.simple
  val CloneableType    = CloneableItem.simple
  val SerializableType = SerializableItem.simple

  // Class Enum
  case object EnumBaseItem extends BaseItem {
    def name = "Enum"
    def isClass = true
    def isEnum = true
    def isFinal = false
    private val E = SimpleTypeVar("E")
    def parent = JavaLangPkg
    val tparams = List(E)
    def base = ObjectType
    val supers = List(base,SerializableType,comparable(E))
    val superItems = List(ObjectItem,SerializableItem,ComparableItem)
  }

  // Simple classes
  sealed abstract class SimpleClassItem extends BaseItem {
    override def simple: SimpleType = SimpleType(this, parent.simple)
    override def parent = JavaLangPkg
    override def tparams = Nil
    def isClass = true
    def isEnum = false
    val interfaces: List[ClassType]
    val base: ClassType
    val supers: List[ClassType]
    val superItems: List[ClassItem]
  }

  // Throwable
  case object ThrowableItem extends SimpleClassItem {
    def name = "Throwable"
    val base = ObjectType
    val interfaces = List(SerializableType)
    val supers = base :: interfaces
    val superItems = supers map (_.item)
    def isFinal = false
  }
  case object ErrorItem extends SimpleClassItem {
    def name = "Error"
    val base = ThrowableType
    val interfaces = Nil
    val supers = List(base)
    val superItems = List(ThrowableItem)
    def isFinal = false
  }
  case object ExceptionItem extends SimpleClassItem {
    def name = "Exception"
    val base = ThrowableType
    val interfaces = Nil
    val supers = List(base)
    val superItems = List(ThrowableItem)
    def isFinal = false
  }
  val ThrowableType = ThrowableItem.simple
  val ExceptionType = ExceptionItem.simple

  // Iterable
  case object IterableItem extends BaseItem {
    def name = "Iterable"
    def isClass = false
    def isEnum = false
    val base = ObjectType
    private val T = SimpleTypeVar("T")
    def parent = JavaLangPkg
    val tparams = List(T)
    val supers = List(base)
    val superItems = List(ObjectItem)
    def isFinal = false
  }

  // Class String
  case object StringItem extends SimpleClassItem {
    def name = "String"
    val base = ObjectType
    lazy val interfaces = List(comparable(inside),CharSequenceItem.simple,SerializableType)
    lazy val supers = interfaces
    val superItems = List(ObjectItem,ComparableItem,CharSequenceItem,SerializableItem)
    def isFinal = true
  }

  // java.lang.Void
  case object VoidItem extends SimpleClassItem {
    def name = "Void"
    val base = ObjectType
    val interfaces = Nil
    val supers = List(base)
    val superItems = supers map (_.item)
    def isFinal = true
  }

  // Reference wrappers around primitive types
  case object BooleanItem extends SimpleClassItem {
    def name = "Boolean"
    val base = ObjectType
    val interfaces = List(comparable(inside),SerializableType)
    val supers = interfaces
    val superItems = supers map (_.item)
    override val unbox = Some(BooleanType)
    override def unboxesToBoolean = true
    def isFinal = true
  }
  case object CharacterItem extends SimpleClassItem {
    def name = "Character"
    val base = ObjectType
    val interfaces = List(comparable(inside),SerializableType)
    val supers = interfaces
    val superItems = supers map (_.item)
    override val unbox = Some(CharType)
    override def unboxNumeric = Some(CharType)
    override def unboxIntegral = Some(CharType)
    def isFinal = true
  }
  case object NumberItem extends SimpleClassItem {
    def name = "Number"
    val base = ObjectType
    val interfaces = List(SerializableType)
    val supers = interfaces
    val superItems = supers map (_.item)
    def isFinal = false
  }
  sealed abstract class NumberClassItem(val name: Name, val ty: NumType) extends SimpleClassItem {
    val base = NumberItem.simple
    val interfaces = List(comparable(inside),SerializableType)
    val supers = base :: interfaces
    val superItems = supers map (_.item)
    override val unbox = Some(ty)
    override def unboxNumeric = Some(ty)
    override def unboxesToNumeric = true
    def isFinal = true
  }
  sealed abstract class IntegralClassItem(name: Name, override val ty: IntegralType) extends NumberClassItem(name,ty) {
    override def unboxIntegral = Some(ty)
  }
  case object ByteItem    extends NumberClassItem("Byte",ByteType)
  case object ShortItem   extends NumberClassItem("Short",ShortType)
  case object IntegerItem extends NumberClassItem("Integer",IntType)
  case object LongItem    extends NumberClassItem("Long",LongType)
  case object FloatItem   extends NumberClassItem("Float",FloatType)
  case object DoubleItem  extends NumberClassItem("Double",DoubleType)

  object ubVoidItem    extends LangTypeItem { def ty = VoidType }
  object ubBooleanItem extends LangTypeItem { def ty = BooleanType }
  object ubByteItem    extends LangTypeItem { def ty = ByteType }
  object ubShortItem   extends LangTypeItem { def ty = ShortType }
  object ubIntItem     extends LangTypeItem { def ty = IntType }
  object ubLongItem    extends LangTypeItem { def ty = LongType }
  object ubFloatItem   extends LangTypeItem { def ty = FloatType }
  object ubDoubleItem  extends LangTypeItem { def ty = DoubleType }
  object ubCharItem    extends LangTypeItem { def ty = CharType }

  // Literals
  val trueLit = LitValue(BooleanLit(true,_))
  val falseLit = LitValue(BooleanLit(false,_))
  val nullLit = LitValue(NullLit)

  // Basic callables for test use.  Overwritten in normal JavaEnvironment processing.
  val ObjectConsItem = DefaultConstructorItem(ObjectItem)
  if (ObjectItem.constructors.length==0)
    ObjectItem.constructors = Array(ObjectConsItem)

  // Classes that have important statics inside should be added to baseItems so we find them in valueByItem
  case object SystemItem extends SimpleClassItem {
    val name = "System"
    val isFinal = true
    val interfaces: List[ClassType] = Nil
    val base: ClassType = ObjectType
    val supers: List[ClassType] = base :: interfaces
    val superItems: List[ClassItem] = supers map (_.item)
  }

  // Standard base environment. Must only contain things that are always visible (although they may be shadowed)
  val baseItems = Array(
    // Packages
    JavaPkg,JavaLangPkg,JavaIoPkg,
    // Primitive types
    ubVoidItem,ubBooleanItem,ubByteItem,ubShortItem,ubIntItem,ubLongItem,ubFloatItem,ubDoubleItem,ubCharItem,
    // Classes
    ObjectItem,VoidItem,
    EnumBaseItem,ThrowableItem,StringItem,BooleanItem,CharacterItem,
    NumberItem,ByteItem,ShortItem,IntegerItem,LongItem,FloatItem,DoubleItem,
    SystemItem,
    // Methods (methods must currently be uniquely identified by name within the class, and they must not be constructors)
    StringEqualsItem, GetClassItem,
    // Interfaces
    CloneableItem,SerializableItem,CharSequenceItem,ComparableItem,IterableItem,
    // Exception base classes
    ThrowableItem, ErrorItem, ExceptionItem,
    // Literals
    trueLit,falseLit,nullLit,
    // Array length
    lengthItem
  )
  val baseSet = baseItems.toSet
  val baseEnv = silenced(Env(baseItems))

  // Things that EnvironmentProcessor won't add on its own
  val extraItems = Array(
    trueLit,falseLit,nullLit,
    ubVoidItem,ubBooleanItem,ubByteItem,ubShortItem,ubIntItem,ubLongItem,ubFloatItem,ubDoubleItem,ubCharItem,
    lengthItem
  )

  // Put all classes and similar at scope level 7
  private def inScopeEnv(items: Array[Item]) = Env(items, (items collect {
    case t@(_:Package|_:ClassItem|_:LangTypeItem|_:LitValue) => (t,7)
  }).toMap)

  val testEnv = silenced(inScopeEnv(baseEnv.allItems))
  val extraEnv = silenced(inScopeEnv(extraItems))
  val extraByItem = valuesByItem(extraItems, false)
}
