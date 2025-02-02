package it.auties.whatsapp.controller;

import com.fasterxml.jackson.annotation.JsonIgnore;
import it.auties.whatsapp.api.ClientType;
import it.auties.whatsapp.model.mobile.PhoneNumber;
import it.auties.whatsapp.util.Json;

import java.util.*;

/**
 * This interface represents is implemented by all WhatsappWeb4J's controllers. It provides an easy
 * way to store IDs and serialize said class.
 */
@SuppressWarnings("unused")
public abstract sealed class Controller<T extends Controller<T>> permits Store, Keys {
    /**
     * The id of this controller
     */
    protected final UUID uuid;

    /**
     * The phone number of the associated companion
     */
    private PhoneNumber phoneNumber;

    /**
     * The serializer instance to use
     */
    @JsonIgnore
    protected ControllerSerializer serializer;

    /**
     * The client type
     */
    protected final ClientType clientType;

    /**
     * A list of alias for the controller, can be used in place of UUID1
     */
    protected final List<String> alias;

    public Controller(UUID uuid, PhoneNumber phoneNumber, ControllerSerializer serializer, ClientType clientType, List<String> alias) {
        this.uuid = uuid;
        this.phoneNumber = phoneNumber;
        this.serializer = serializer;
        this.clientType = clientType;
        this.alias = Objects.requireNonNullElseGet(alias, ArrayList::new);
    }

    /**
     * Serializes this object
     *
     * @param async whether the operation should be executed asynchronously
     */
    public abstract void serialize(boolean async);

    /**
     * Disposes this object
     */
    public abstract void dispose();

    public UUID uuid() {
        return this.uuid;
    }

    public ClientType clientType() {
        return this.clientType;
    }

    /**
     * Returns the phone number of this controller
     *
     * @return an optional
     */
    public Optional<PhoneNumber> phoneNumber() {
        return Optional.ofNullable(phoneNumber);
    }

    /**
     * Sets the phone number used by this session
     *
     * @return the same instance
     */
    @SuppressWarnings("unchecked")
    public T setPhoneNumber(PhoneNumber phoneNumber) {
        this.phoneNumber = phoneNumber;
        serializer.linkMetadata(this);
        return (T) this;
    }

    /**
     * Returns the serializer
     *
     * @return a non-null serializer
     */
    public ControllerSerializer serializer() {
        return serializer;
    }

    /**
     * Sets the serializer of this controller
     *
     * @param serializer a serializer
     * @return the same instance
     */
    @SuppressWarnings("unchecked")
    public T setSerializer(ControllerSerializer serializer) {
        this.serializer = serializer;
        return (T) this;
    }

    /**
     * Returns an immutable collection of alias
     *
     * @return an immutable collection
     */
    public Collection<String> alias() {
        return Collections.unmodifiableList(alias);
    }

    /**
     * Adds an alias to this controller
     *
     * @param entry the non-null alias to add
     */
    public void addAlias(String entry) {
        alias.add(entry);
    }

    /**
     * Removes an alias to this controller
     *
     * @param entry the non-null alias to remove
     */
    public void removeAlias(String entry) {
        alias.remove(entry);
    }

    /**
     * Removes all alias from this controller
     */
    public void removeAlias() {
        alias.clear();
    }

    /**
     * Converts this controller to a json. Useful when debugging.
     *
     * @return a non-null string
     */
    public String toJson() {
        return Json.writeValueAsString(this, true);
    }

    /**
     * Deletes the current session
     */
    public void deleteSession() {
        serializer.deleteSession(this);
    }
}
