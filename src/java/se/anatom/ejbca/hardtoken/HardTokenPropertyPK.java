/*
 * Generated by XDoclet - Do not edit!
 */
package se.anatom.ejbca.hardtoken;

/**
 * Primary key for HardTokenPropertyData.
 */
public class HardTokenPropertyPK
   extends java.lang.Object
   implements java.io.Serializable
{

   public java.lang.String id;
   public java.lang.String property;

   public HardTokenPropertyPK()
   {
   }

   public HardTokenPropertyPK( java.lang.String id,java.lang.String property )
   {
      this.id = id;
      this.property = property;
   }

   public java.lang.String getId()
   {
      return id;
   }
   public java.lang.String getProperty()
   {
      return property;
   }

   public void setId(java.lang.String id)
   {
      this.id = id;
   }
   public void setProperty(java.lang.String property)
   {
      this.property = property;
   }

   public int hashCode()
   {
      int _hashCode = 0;
         if (this.id != null) _hashCode += this.id.hashCode();
         if (this.property != null) _hashCode += this.property.hashCode();

      return _hashCode;
   }

   public boolean equals(Object obj)
   {
      if( !(obj instanceof se.anatom.ejbca.hardtoken.HardTokenPropertyPK) )
         return false;

      se.anatom.ejbca.hardtoken.HardTokenPropertyPK pk = (se.anatom.ejbca.hardtoken.HardTokenPropertyPK)obj;
      boolean eq = true;

      if( obj == null )
      {
         eq = false;
      }
      else
      {
         if( this.id != null )
         {
            eq = eq && this.id.equals( pk.getId() );
         }
         else  // this.id == null
         {
            eq = eq && ( pk.getId() == null );
         }
         if( this.property != null )
         {
            eq = eq && this.property.equals( pk.getProperty() );
         }
         else  // this.property == null
         {
            eq = eq && ( pk.getProperty() == null );
         }
      }

      return eq;
   }

   /** @return String representation of this pk in the form of [.field1.field2.field3]. */
   public String toString()
   {
      StringBuffer toStringValue = new StringBuffer("[.");
         toStringValue.append(this.id).append('.');
         toStringValue.append(this.property).append('.');
      toStringValue.append(']');
      return toStringValue.toString();
   }

}
