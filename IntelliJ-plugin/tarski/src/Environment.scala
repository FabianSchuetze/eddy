package tarski

import Semantics.Score
import AST.Name

import scala.collection.mutable

object Environment {

  // represents a language item (these are given to us by someone who knows about the surrounding code)
  sealed abstract class EnvItem

  sealed abstract class NamedItem(val name: Name) extends EnvItem {
    // used for name matching
    def qualifiedName: Name
    def relativeName: Name
  }

  sealed abstract class Modifier
  object PublicModifier extends Modifier
  object ProtectedModifier extends Modifier
  object PrivateModifier extends Modifier
  object StaticModifier extends Modifier
  object FinalModifier extends Modifier
  object StrictfpModifier extends Modifier
  object TransientModifier extends Modifier
  object VolatileModifier extends Modifier
  object SynchronizedModifier extends Modifier

  // EnvItems that are a type-like thing
  sealed abstract class TypeItem(name: Name, val dims: Int = 0, val mods: List[Modifier] = Nil) extends NamedItem(name)

  // NamedItems that have a type
  sealed abstract class HasTypeItem(name: Name, val ourType: TypeItem) extends NamedItem(name)

  // a method or constructor
  sealed abstract class CallableItem(name: Name, val paramTypes: List[TypeItem]) extends NamedItem(name)

  // stuff
  sealed abstract class PackageItem(name: Name) extends NamedItem(name)
  sealed abstract class AnnotationItem(name: Name) extends NamedItem(name)

  // types
  sealed abstract class BasicTypeItem(name: Name) extends TypeItem(name) {
    def qualifiedName = name
    def relativeName = name
  }

  // Java basic types (they'll also be in the environment, but they're nice to have around)
  object BooleanItem extends BasicTypeItem("boolean")
  object IntItem extends BasicTypeItem("int")
  object FloatItem extends BasicTypeItem("float")
  object LongItem extends BasicTypeItem("long")
  object DoubleItem extends BasicTypeItem("double")
  object CharItem extends BasicTypeItem("char")

  sealed abstract class ClassItem(name: Name) extends TypeItem(name)
  sealed abstract class EnumItem(name: Name) extends TypeItem(name)
  sealed abstract class InterfaceItem(name: Name) extends TypeItem(name)

  // things with types
  sealed abstract class EnumConstantItem(name: Name, ourType: EnumItem) extends HasTypeItem(name, ourType)
  sealed abstract class FieldItem(name: Name, ourType: TypeItem) extends HasTypeItem(name, ourType)
  sealed abstract class ParameterItem(name: Name, ourType: TypeItem) extends HasTypeItem(name, ourType)
  sealed abstract class LocalVariableItem(name: Name, ourType: TypeItem) extends HasTypeItem(name, ourType)

  // callables
  sealed abstract class MethodItem(name: Name, val retVal: TypeItem, paramTypes: List[TypeItem]) extends CallableItem(name, paramTypes)
  sealed abstract class ConstructorItem(name: Name, val cls: ClassItem, paramTypes: List[TypeItem]) extends CallableItem(name, paramTypes)

  // things that are created by us
  // TODO: probably need all we have above here
  sealed abstract class NewVariableItem(name: Name, t: TypeItem) extends NamedItem(name)
  sealed abstract class NewTypeItem(name: Name) extends TypeItem(name)
  sealed abstract class NewMethodItem(name: Name) extends NamedItem(name)

  // when we cannot assign anything useful to this node
  sealed class ErrorItem() extends EnvItem


  // These class implementations are created from the plugin side. They implement the matching interface defined in NamedItem
  class PackageItemImpl(name: Name, val qualifiedName: Name, val relativeName: Name) extends PackageItem(name)
  class ClassItemImpl(name: Name, val qualifiedName: Name, val relativeName: Name) extends ClassItem(name)
  class ParameterItemImpl(name: Name, ourType: TypeItem) extends ParameterItem(name, ourType) { def qualifiedName = null; def relativeName = null }
  class EnumConstantItemImpl(name: Name, ourType: EnumItem, val qualifiedName: Name, val relativeName: Name) extends EnumConstantItem(name, ourType)
  class LocalVariableItemImpl(name: Name, ourType: TypeItem) extends LocalVariableItem(name, ourType)  { def qualifiedName = null; def relativeName = null }
  class FieldItemImpl(name: Name, ourType: TypeItem, val qualifiedName: Name, val relativeName: Name) extends FieldItem(name, ourType)


  /**
   * Contains the environment used for name resolution
   */
  class JavaEnvironment {

    // object storage, comes pre-spawned with Java's basic types
    var things: mutable.MutableList[NamedItem] = mutable.MutableList(BooleanItem, IntItem, FloatItem, LongItem, DoubleItem, CharItem)

    // used on plugin side to fill in data
    def addObject(thing: NamedItem): Unit = things += thing

    // is this object assignable (not final) in our current context?
    def isAssignable(thing: EnvItem): Boolean = {
      false // TODO
    }

    // whether from can be implicitly converted to to
    def convertibleTo(from: TypeItem, to: TypeItem): Boolean = {
      false // TODO
    }

    // whether from can be explicitly cast to to
    def castableTo(from: TypeItem, to: TypeItem): Boolean = {
      false // TODO
    }

    // Fuzzy Query interface

    // what could this name be?
    def getScores(name: String): List[(Score, EnvItem)] = { Nil }

    // what could this name be, assuming it is a type?
    def getTypeScores(name: String): List[(Score, EnvItem)] = { Nil }

    // what could this name be, assuming it is an annotation
    def getAnnotationScores(name: String): List[(Score, EnvItem)] = { Nil }

    // give us a list of fields for this type
    def getFieldScores(t: TypeItem, name: String): List[(Score, EnvItem)] = { Nil }

    // give a list of fields for this package
    def getFieldScores(t: PackageItem, name: String): List[(Score, EnvItem)] = { Nil }

    // give us a list of type fields for this type
    def getFieldTypeScores(t: TypeItem, name: String): List[(Score, EnvItem)] = { Nil }
  }

}